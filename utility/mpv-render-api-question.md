# mpv_render_context_create fails with -18 (not supported) / "Can't load OpenGL functions" — get_proc_address callback never invoked

**Setup:** Windows x64, embedding libmpv's render API (`MPV_RENDER_API_TYPE_OPENGL`) into a Kotlin/JVM
application via JNA, supplying an externally-created current OpenGL context (from JetBrains Skiko/Compose
Desktop's `WindowsOpenGLRedrawer`). No media is loaded; this is a minimal reproduction: create an mpv core,
set `vo=libmpv` and `gpu-api=opengl`, initialize, then call `mpv_render_context_create` from inside a
callback where the Skiko-owned WGL context is current on the calling thread.

**Two independent libmpv builds tested, both fail identically:**
1. The project's normally-bundled `libmpv-2.dll` (`mpv_client_api_version` = 2.5)
2. A full "dev" build from shinchiro/mpv-winbuild-cmake (`mpv-dev-x86_64-20260830-git-e8673660ab.7z`,
   expected to include full OpenGL GPU-context support)

**Symptom:** `mpv_render_context_create` returns `-18` (`MPV_ERROR_NOT_IMPLEMENTED` / "not supported").
With `msg-level=all=trace` and `mpv_request_log_messages`/`mpv_wait_event` draining the log queue, the
only relevant log lines are:

```
[libmpv_render] error: Can't load OpenGL functions.
[libmpv_render] fatal: OpenGL not initialized.
```

**What's been ruled out with direct evidence:**

- **Context currency/version:** Added a diagnostic calling `wglGetCurrentContext()` and
  `glGetString(GL_VERSION)` directly (bypassing mpv) at the exact point `mpv_render_context_create` is
  called. Confirmed a real, current, modern context: `wglGetCurrentContext=native@0x10000`,
  `GL_VERSION=4.6.0` (real Intel driver). Far above mpv's documented GL 2.1 minimum.
- **get_proc_address resolver correctness:** The callback was implemented per `render_gl.h`'s documented
  requirement — try `wglGetProcAddress` first, treating null/1/2/3/-1 as failure, falling back to a direct
  `GetProcAddress`-style lookup in `opengl32.dll` for GL 1.1 core entry points. Did not fix it.
- **Struct/callback marshaling:** After writing the `mpv_opengl_init_params` structure, read back its raw
  backing memory directly and confirmed a real, non-null native function pointer is present at the
  `get_proc_address` field offset, matching a live JNA callback trampoline for our resolver function. The
  callback is genuinely, correctly installed in the params mpv receives.
- **Decisive finding:** instrumented the `get_proc_address` callback to log every invocation with the
  requested function name. **It is called zero times** across the entire failing
  `mpv_render_context_create` call. mpv logs "Can't load OpenGL functions" and fails without ever once
  invoking the resolver it was given.

**Also tried:** setting `gpu-context=wgl` explicitly before init — `mpv_set_option_string` itself rejects
this with a negative error, meaning that option name isn't recognized in this configuration/build at all
(different failure mode, not attempted as a real fix candidate at that point).

**Question:** Given the callback is never invoked at all, what earlier validation inside
`mpv_render_context_create` (for `MPV_RENDER_API_TYPE_OPENGL` specifically, on Windows with `vo=libmpv`)
could reject the render context *before* attempting to resolve any GL function through the supplied
`get_proc_address`? Is there a known interaction between `vo=libmpv` and the OpenGL render backend on
Windows builds that would explain this, independent of the caller-supplied context/callback?

**Repro details available on request:** exact render-parameter array construction, structure layouts, DLL
build/version details, SHA-256 hashes of both tested DLLs.
