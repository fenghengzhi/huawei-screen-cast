package com.local.huaweicast;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.nio.*;
import java.util.concurrent.*;

/** Keeps the encoder fed even when the mirrored display stops producing buffers. */
public final class FrameRepeater {
    private final HandlerThread thread = new HandlerThread("cast-frame-clock");
    private final Handler handler;
    private final ThreadBoundCleanup releaseTask;
    private final Surface encoderSurface;
    private final int width, height, fps;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface source;
    private int program, textureId;
    private boolean newFrame, hasFrame, stopped;
    private volatile RuntimeException failure;
    private final float[] transform = new float[16];
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private long frameOrigin, scheduledFrames;

    public FrameRepeater(Surface encoderSurface, int width, int height, int fps) throws Exception {
        this.encoderSurface = encoderSurface; this.width = width; this.height = height; this.fps = fps;
        vertices.put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1}).position(0);
        thread.start(); handler = new Handler(thread.getLooper());
        releaseTask = new ThreadBoundCleanup(() -> Thread.currentThread() == thread, task -> {
            if (!handler.post(task)) throw new IllegalStateException("渲染线程已停止，无法安排清理");
        }, () -> {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            try { cleanup(); }
            finally { thread.quitSafely(); }
        });
        CountDownLatch ready = new CountDownLatch(1);
        handler.post(() -> { try { initialize(); } catch (RuntimeException error) { failure = error; release(); } finally { ready.countDown(); } });
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) { release(); throw new IllegalStateException("GPU 初始化超时"); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            release();
            throw error;
        }
        if (failure != null) { release(); throw failure; }
    }
    public Surface getSurface() { return source; }
    public RuntimeException getFailure() { return failure; }
    private void initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("EGL 初始化失败");
        int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new IllegalStateException("无可用录屏 EGL 配置");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
        window = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, window, window, context)) throw new IllegalStateException("无法绑定编码 Surface");
        int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec2 uv; uniform mat4 transform; varying vec2 tex; void main(){gl_Position=position;tex=(transform*vec4(uv,0.,1.)).xy;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 tex; void main(){gl_FragColor=texture2D(image,tex);}");
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); textureId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId); texture.setDefaultBufferSize(width, height);
        texture.setOnFrameAvailableListener(t -> newFrame = true, handler);
        source = new Surface(texture); frameOrigin = SystemClock.uptimeMillis(); handler.post(this::render);
    }
    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] compiled = new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);
        if (compiled[0] == 0) { String error = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IllegalStateException(error); }
        return shader;
    }
    private void render() {
        if (stopped) return;
        try {
            if (newFrame) { texture.updateTexImage(); texture.getTransformMatrix(transform); newFrame = false; hasFrame = true; }
            if (hasFrame) {
                GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program);
                int position = GLES20.glGetAttribLocation(program,"position"), uv = GLES20.glGetAttribLocation(program,"uv");
                vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(position);
                vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,transform,0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId); GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                EGLExt.eglPresentationTimeANDROID(display,window,System.nanoTime());
                if (!EGL14.eglSwapBuffers(display,window)) throw new IllegalStateException("GPU 提交画面失败");
            }
            scheduledFrames = Math.max(scheduledFrames + 1, (SystemClock.uptimeMillis() - frameOrigin) * fps / 1000 + 1);
            handler.postAtTime(this::render, frameOrigin + scheduledFrames * 1000 / fps);
        } catch (RuntimeException error) { failure = error; stopped = true; android.util.Log.e("ScreenMirror", "固定帧率渲染失败", error); }
    }
    public void release() {
        releaseTask.release(2, TimeUnit.SECONDS);
    }
    private void cleanup() {
        if (source != null) { source.release(); source = null; }
        if (texture != null) { texture.release(); texture = null; }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
        if (textureId != 0) { GLES20.glDeleteTextures(1,new int[]{textureId},0); textureId = 0; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display); EGL14.eglReleaseThread(); display = EGL14.EGL_NO_DISPLAY;
        }
    }
}
