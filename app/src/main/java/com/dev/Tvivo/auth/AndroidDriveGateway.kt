package com.dev.Tvivo.auth

import android.content.Context
import com.dev.Tvivo.BuildConfig
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.DriveIndexStateEntity
import com.dev.Tvivo.data.repository.DriveRepository
import com.dev.Tvivo.source.AuthorizationStep
import com.dev.Tvivo.source.SourceAccessToken
import com.dev.Tvivo.source.SourceAuthorization
import com.dev.Tvivo.source.SourceId
import com.dev.Tvivo.source.drive.*
import com.dev.Tvivo.source.library.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Android transport for the shared Drive ports. The mock path exists in debug builds only. */
class AndroidDriveGateway(context: Context) : DriveFlowGateway {
    private val app = context.applicationContext
    private val db by lazy { AppDatabase.get(app) }
    private val http = OkHttpClient.Builder().followRedirects(false).build()
    private val mockBase = if (BuildConfig.DEBUG) BuildConfig.DRIVE_MOCK_BASE_URL else ""
    private val apiBase = if (mockBase.isNotEmpty()) "$mockBase/drive/v3" else "https://www.googleapis.com/drive/v3"
    private var authorization: SourceAuthorization? = null
    private var pendingRefreshToken: String? = null
    private var pendingPermissionId: String? = null
    private var pendingRootId: String? = null

    override suspend fun begin(isTelevision: Boolean): AuthorizationStep {
        if (!BuildConfig.DRIVE_SOURCE_ENABLED) error("Google Drive is not configured")
        authorization = if (isTelevision) {
            if (BuildConfig.DRIVE_TV_CLIENT_ID.isBlank()) error("TV OAuth client ID is not configured")
            DeviceCodeSourceAuthorization(
                http = OkHttpDeviceCodeHttp(http),
                tokens = object : RefreshTokenStore {
                    override suspend fun load() = pendingRefreshToken
                    override suspend fun save(token: String?) { pendingRefreshToken = token }
                },
                endpoints = if (mockBase.isNotEmpty()) DeviceCodeEndpoints(
                    "$mockBase/device/code", "$mockBase/token", "$mockBase/revoke"
                ) else DeviceCodeEndpoints()
            )
        } else {
            if (mockBase.isEmpty()) error("Phone Google Drive consent is not configured")
            PhoneSourceAuthorization(MockPhoneAuthorizationClient(mockBase, http))
        }
        return requireNotNull(authorization).begin()
    }

    override suspend fun continueAuthorization(): AuthorizationStep =
        requireNotNull(authorization).continueAuthorization()

    override suspend fun prepare(onStep: (BootstrapStep) -> Unit): DrivePreparation {
        val auth = requireNotNull(authorization) { "Google Drive is not configured" }
        val token = auth.accessToken() ?: throw DriveFailure.Unauthorized
        val about = about(token)
        val permissionId = about.permissionId.takeIf { it.isNotBlank() }
            ?: error("Google Drive did not return a permission ID")
        pendingPermissionId = permissionId
        val sourceId = SourceId.drive(permissionId)
        val drive = DriveIndexStore(transport(), { auth.accessToken() ?: throw DriveFailure.Unauthorized }, apiBase)
        val bootstrapState = RoomBootstrapState(db, sourceId.value)
        val result = DriveBootstrap(drive, bootstrapState, onStep,
            seedContent = { if (mockBase.isNotEmpty()) mockSeed() else Seed.buildSeedContent() },
            allowMockHttp = mockBase.isNotEmpty()).run()
        if (result is BootstrapResult.NeedsChoice) return DrivePreparation.Choose(result.rootIds)
        result as BootstrapResult.Ready
        pendingRootId = result.rootId
        val repo = DriveRepository(db, sourceId)
        db.profileDao().ensureAdult(sourceId.value, System.currentTimeMillis())
        val sync = DriveSyncEngine(sourceId.value, drive, RoomSyncState(db, sourceId.value),
            RoomDriveApplier(repo), allowMockHttp = mockBase.isNotEmpty()).sync(result.rootId)
        if (sync is SyncResult.Failure) throw sync.cause
        val saved = SavedSource.drive(permissionId, pendingRefreshToken)
        val sourceStore = SourceStore.open(app)
        check(sourceStore.stageSwitch(saved) { true })
        val last = listOf("movies", "series", "music")
            .mapNotNull { db.driveDao().state(sourceId.value, it)?.lastSuccessfulSyncAt }.maxOrNull()
        return DrivePreparation.Ready(DriveAccount(
            SourceSession.Drive(saved.id, permissionId), about.name, about.email, result.rootId, last
        ), result.seeded)
    }

    override suspend fun chooseFolder(id: String) {
        val permissionId = requireNotNull(pendingPermissionId)
        RoomBootstrapState(db, SourceId.drive(permissionId).value).save(BootstrapState(id))
    }

    override suspend fun cancel() {
        (authorization as? DeviceCodeSourceAuthorization)?.cancel()
        authorization = null
        pendingRefreshToken = null
        pendingPermissionId = null
        pendingRootId = null
    }

    suspend fun savedAccount(session: SourceSession.Drive): DriveAccount {
        val state = db.driveDao().state(session.id, "__bootstrap")
        val last = listOf("movies", "series", "music")
            .mapNotNull { db.driveDao().state(session.id, it)?.lastSuccessfulSyncAt }.maxOrNull()
        return DriveAccount(session, null, null, state?.folderId, last)
    }

    suspend fun refreshIdentity(account: DriveAccount): DriveAccount {
        val about = runCatching { about(savedAuthorization(account.session).accessToken()
            ?: throw DriveFailure.Unauthorized) }.getOrNull() ?: return account
        return account.copy(displayName = about.name, email = about.email)
    }

    suspend fun resync(session: SourceSession.Drive): Long {
        val auth = savedAuthorization(session)
        val rootId = RoomBootstrapState(db, session.id).load().rootId
            ?: error("Google Drive library folder is missing")
        val repo = DriveRepository(db, SourceId.drive(session.permissionId))
        val drive = DriveIndexStore(transport(), { auth.accessToken() ?: throw DriveFailure.Unauthorized }, apiBase)
        val result = DriveSyncEngine(session.id, drive, RoomSyncState(db, session.id),
            RoomDriveApplier(repo), allowMockHttp = mockBase.isNotEmpty()).sync(rootId)
        if (result is SyncResult.Failure) throw result.cause
        return listOf("movies", "series", "music")
            .mapNotNull { db.driveDao().state(session.id, it)?.lastSuccessfulSyncAt }.maxOrNull()
            ?: System.currentTimeMillis()
    }

    suspend fun disconnect(session: SourceSession.Drive): Boolean {
        val auth = savedAuthorization(session)
        return SourceStore.open(app).disconnect(session.id) { auth.revoke() }
    }

    private suspend fun savedAuthorization(session: SourceSession.Drive): SourceAuthorization {
        if (mockBase.isNotEmpty() && pendingPermissionId == session.permissionId && authorization != null) {
            return requireNotNull(authorization)
        }
        val saved = SourceStore.open(app).load().sources.firstOrNull { it.id == session.id }
        return if (mockBase.isNotEmpty() && saved?.refreshToken == null) {
            PhoneSourceAuthorization(MockPhoneAuthorizationClient(mockBase, http))
        } else DeviceCodeSourceAuthorization(
            http = OkHttpDeviceCodeHttp(http),
            tokens = SourceStore.open(app).refreshTokens(session.id),
            endpoints = if (mockBase.isNotEmpty()) DeviceCodeEndpoints(
                "$mockBase/device/code", "$mockBase/token", "$mockBase/revoke"
            ) else DeviceCodeEndpoints()
        )
    }

    private data class About(val permissionId: String, val name: String?, val email: String?)
    private suspend fun about(token: SourceAccessToken): About = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(Request.Builder().url("$apiBase/about?fields=user(permissionId,displayName,emailAddress)")
                .header("Authorization", "Bearer ${token.value}").build()).execute()
        } catch (_: java.io.IOException) { throw DriveFailure.Offline }
        response.use {
            if (response.code == 401) throw DriveFailure.Unauthorized
            if (response.code == 403) {
                val body = response.body?.string().orEmpty()
                if (body.contains("storageQuotaExceeded")) throw DriveFailure.QuotaExceeded
                throw DriveFailure.Forbidden
            }
            if (response.code == 429) throw DriveFailure.RateLimited(null)
            if (!response.isSuccessful) throw DriveFailure.Offline
            val user = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject.getAsJsonObject("user")
            About(user?.get("permissionId")?.asString.orEmpty(),
                user?.get("displayName")?.asString, user?.get("emailAddress")?.asString)
        }
    }

    private fun transport() = DriveHttpClient { request -> withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val body = request.body?.toRequestBody(
            (request.headers["Content-Type"] ?: "application/octet-stream").toMediaType())
        builder.method(request.method, body)
        http.newCall(builder.build()).execute().use { response ->
            DriveHttpResponse(response.code, response.headers.toMultimap().mapValues { it.value.first() },
                response.body?.bytes() ?: byteArrayOf())
        }
    } }

    private suspend fun mockSeed(): SeedContent = withContext(Dispatchers.IO) {
        val json = http.newCall(Request.Builder().url("$mockBase/seed").build()).execute().use {
            if (!it.isSuccessful) throw DriveFailure.Offline
            JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject
        }
        fun <T> items(result: ParseResult<ParsedList<T>>) =
            (result as? ParseResult.Valid)?.items?.items ?: error("Invalid mock seed")
        SeedContent(items(parseMovies(json.getAsJsonObject("movies").toString(), true)),
            items(parseSeries(json.getAsJsonObject("series").toString(), true)),
            items(parseMusic(json.getAsJsonObject("music").toString(), true)))
    }
}

private class RoomBootstrapState(private val db: AppDatabase, private val id: String) : BootstrapStateStore {
    override suspend fun load(): BootstrapState {
        val row = db.driveDao().state(id, "__bootstrap") ?: return BootstrapState()
        return BootstrapState(row.folderId, row.bootstrapState.startsWith("pending"),
            row.bootstrapState == "pending-created")
    }
    override suspend fun save(state: BootstrapState) {
        db.driveDao().upsertState(DriveIndexStateEntity(id, "__bootstrap", folderId = state.rootId,
            bootstrapState = if (state.pending && state.createdByApp) "pending-created"
                else if (state.pending) "pending" else "ready"))
    }
}

private class RoomSyncState(private val db: AppDatabase, private val id: String) : SyncStateStore {
    override suspend fun fingerprint(kind: String): IndexFingerprint? = db.driveDao().state(id, kind)?.let {
        it.fileId?.let { file -> IndexFingerprint(file, it.version, it.md5Checksum, it.modifiedAtMillis) }
    }
    override suspend fun saveFingerprint(kind: String, value: IndexFingerprint) {
        val previous = db.driveDao().state(id, kind) ?: DriveIndexStateEntity(id, kind)
        db.driveDao().upsertState(previous.copy(fileId = value.id, version = value.version,
            md5Checksum = value.md5, modifiedAtMillis = value.modifiedAtMillis))
    }
}

private class RoomDriveApplier(private val repo: DriveRepository) : DriveIndexApplier {
    override suspend fun movies(value: ParseResult<ParsedList<MovieEntry>>, syncedAt: Long) { check(repo.applyMovies(value, syncedAt)) }
    override suspend fun series(value: ParseResult<ParsedList<ShowEntry>>, syncedAt: Long) { check(repo.applySeries(value, syncedAt)) }
    override suspend fun music(value: ParseResult<ParsedList<SongEntry>>, syncedAt: Long) { check(repo.applyMusic(value, syncedAt)) }
}

/** Debug phone consent fake. It obtains a real mock bearer, then enters through PhoneSourceAuthorization. */
private class MockPhoneAuthorizationClient(private val base: String, private val http: OkHttpClient) : PhoneAuthorizationClient {
    override suspend fun authorize(): PhoneAuthorizationClient.Result = withContext(Dispatchers.IO) {
        val code = http.newCall(Request.Builder().url("$base/device/code")
            .post(FormBody.Builder().add("account", "account-a").build()).build()).execute().use {
            if (!it.isSuccessful) throw DriveFailure.Offline
            JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject.get("device_code").asString
        }
        val approve = "{\"device_code\":\"$code\"}".toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/__ctl/approve").post(approve).build()).execute().use {
            if (!it.isSuccessful) throw DriveFailure.Offline
        }
        val token = http.newCall(Request.Builder().url("$base/token")
            .post(FormBody.Builder().add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", code).build()).build()).execute().use {
            if (!it.isSuccessful) throw DriveFailure.Offline
            JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject.get("access_token").asString
        }
        PhoneAuthorizationClient.Result.Token(token)
    }
    override suspend fun continueConsent(requestId: String) = authorize()
    override suspend fun revokeAccess() = true
}
