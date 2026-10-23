package com.dev.Tvivo.auth

import com.dev.Tvivo.source.AuthorizationStep
import com.dev.Tvivo.source.drive.BootstrapStep
import com.dev.Tvivo.source.drive.DriveFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    private class FakeGateway : DriveFlowGateway {
        var beginStep: AuthorizationStep = AuthorizationStep.Ready
        var preparation: DrivePreparation = DrivePreparation.Ready(
            DriveAccount(SourceSession.Drive("drive:test", "permission"), "Name", "email@example.test", "folder", 10L), false)
        var failure: Throwable? = null
        var cancelled = false
        var chosen: String? = null
        var polls = 0
        override suspend fun begin(isTelevision: Boolean) = beginStep
        override suspend fun continueAuthorization(): AuthorizationStep { polls++; return AuthorizationStep.Pending }
        override suspend fun prepare(onStep: (BootstrapStep) -> Unit): DrivePreparation {
            onStep(BootstrapStep.FINDING_LIBRARY)
            onStep(BootstrapStep.CREATING_FOLDERS)
            onStep(BootstrapStep.ADDING_SAMPLE_LINKS)
            onStep(BootstrapStep.OPENING_SAVED_LIBRARY)
            failure?.let { throw it }
            return preparation
        }
        override suspend fun chooseFolder(id: String) { chosen = id; preparation = DrivePreparation.Ready(
            DriveAccount(SourceSession.Drive("drive:test", "permission"), null, null, id, 11L), false) }
        override suspend fun cancel() { cancelled = true }
    }

    @Test fun bootstrapReadyAndFolderChoice() = runTest(dispatcher) {
        val fake = FakeGateway().apply { preparation = DrivePreparation.Choose(listOf("one", "two")) }
        val model = DriveFlowViewModel(fake)
        model.start(false)
        advanceUntilIdle()
        assertEquals(DriveFlowState.ChooseFolder(listOf("one", "two")), model.state.value)
        model.chooseFolder("two")
        advanceUntilIdle()
        assertEquals("two", fake.chosen)
        assertEquals("two", (model.state.value as DriveFlowState.Ready).account.folderId)
    }

    @Test fun quotaKeepsRetryableFailureAndRetryCompletes() = runTest(dispatcher) {
        val fake = FakeGateway().apply { failure = DriveFailure.QuotaExceeded }
        val model = DriveFlowViewModel(fake)
        model.start(false)
        advanceUntilIdle()
        assertTrue((model.state.value as DriveFlowState.Failed).message.contains("Drive is full"))
        fake.failure = null
        model.retry()
        advanceUntilIdle()
        assertTrue(model.state.value is DriveFlowState.Ready)
    }

    @Test fun cancellingDeviceCodeStopsPollingAndReturnsIdle() = runTest(dispatcher) {
        val fake = FakeGateway().apply { beginStep = AuthorizationStep.DeviceCode("https://example.test", "Case-A", 60_000L, 5) }
        val model = DriveFlowViewModel(fake)
        model.start(true)
        runCurrent()
        assertTrue(model.state.value is DriveFlowState.DeviceCode)
        model.cancel()
        runCurrent()
        assertTrue(fake.cancelled)
        assertEquals(0, fake.polls)
        assertEquals(DriveFlowState.Idle, model.state.value)
    }
}
