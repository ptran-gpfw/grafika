/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.TextureView;

import java.io.File;

import static com.android.grafika.gles.EglCore.FLAG_TRY_GLES3;

/**
 * Draws an image sourced from a file on to TextureView / SurfaceTexture using GLES.
 */
public class TextureViewImageActivity extends Activity {
    private static final String TAG = TextureViewImageActivity.class.getSimpleName();

    private File mImageFile = new File("/sdcard/DCIM/test.png");
    private TextureTask mTextureTask;
    private TextureView mTextureView;
    private EglCore mEglCore = null;
    private WindowSurface mWindowSurface = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture_view_image);

        mTextureTask = new TextureTask();

        mTextureView = findViewById(R.id.glTextureView);
        mTextureView.setSurfaceTextureListener(mTextureTask);
    }


    @Override
    protected void onResume() {
        super.onResume();
        mTextureTask.execute();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTextureTask.release();
    }

    /**
     * AsyncTask class that executes the test.
     */
    private class TextureTask extends AsyncTask<Void, Integer, Long>
            implements TextureView.SurfaceTextureListener {
        private Object mLock = new Object();        // guards mSurfaceTexture, mDone
        private SurfaceTexture mSurfaceTexture;
        private boolean mDone;

        private int mTextureHandle = -1;
        private Sprite2d mRect;
        Texture2dProgram mTexProgram;

        /**
         * Prepare for the glTexImage2d test.
         */
        public TextureTask() {
        }

        @Override
        protected Long doInBackground(Void... params) {
            long result = -1;

            // TODO: this should not use AsyncTask.  The AsyncTask worker thread is run at
            // a lower priority, making it unsuitable for benchmarks.  We can counteract
            // it in the current implementation, but this is not guaranteed to work in
            // future releases.
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            SurfaceTexture surfaceTexture = null;

            // Latch the SurfaceTexture when it becomes available.  We have to wait for
            // the TextureView to create it.
            synchronized (mLock) {
                while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);     // not expected
                    }
                }
            }
            Log.d(TAG, "Got surfaceTexture=" + surfaceTexture);

            try {
                mEglCore = new EglCore(null, FLAG_TRY_GLES3);
                mWindowSurface = new WindowSurface(mEglCore, surfaceTexture);
                result = runTextureTest(mWindowSurface);
            }
            catch(Exception e) {
                Log.e(TAG, "", e);
            }
            return result;
        }

        public void release() {
            synchronized (mLock) {
                if (mWindowSurface != null) {
                    mWindowSurface.release();
                }
                if (mEglCore != null) {
                    mEglCore.release();
                }
            }
        }

        /**
         * Tells the thread to stop running.
         */
        public void halt() {
            synchronized (mLock) {
                mDone = true;
                mLock.notify();
            }
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            Log.d(TAG, "onSurfaceTextureAvailable(" + width + "x" + height + ")");
            synchronized (mLock) {
                mSurfaceTexture = st;
                mLock.notify();
            }
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
            // TODO: ?
        }

        @Override   // will be called on UI thread
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            Log.d(TAG, "onSurfaceTextureDestroyed");

            // We set the SurfaceTexture reference to null to tell the Renderer thread that
            // it needs to stop.  The renderer might be in the middle of drawing, so we want
            // to return false here so that the caller doesn't try to release the ST out
            // from under us.
            //
            // In theory.
            //
            // In 4.4, the buffer queue was changed to be synchronous, which means we block
            // in dequeueBuffer().  If the renderer has been running flat out and is currently
            // sleeping in eglSwapBuffers(), it's going to be stuck there until somebody
            // tears down the SurfaceTexture.  So we need to tear it down here to ensure
            // that the renderer thread will break.  If we don't, the thread sticks there
            // forever.
            //
            // The only down side to releasing it here is we'll get some complaints in logcat
            // when eglSwapBuffers() fails.
            synchronized (mLock) {
                mSurfaceTexture = null;
            }

            return true;
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            //Log.d(TAG, "onSurfaceTextureUpdated");
        }

        //Marshmallow permissions needed, ie
        // adb shell pm grant com.google.grafika android.permission.READ_EXTERNAL_STORAGE
        private int loadImageIntoTexture(File imagePath) {
            int textureHandle;
            Log.d(TAG, "load: " + imagePath.toString());

            Bitmap bitmap = BitmapFactory.decodeFile(imagePath.getPath());
            Log.d(TAG, "load: decoded: " + imagePath.toString());

            textureHandle = createTextureObject(GLES31.GL_TEXTURE_2D, GLES31.GL_RGBA8, bitmap.getWidth(), bitmap.getHeight());
            Log.d(TAG, "load: textureHandle," + textureHandle);

            GLUtils.texSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            GlUtil.checkGlError("texSubImage2D");
            return textureHandle;
        }

        private int createTextureObject(int textureTarget, int format, int width, int height) {
            int[] textures = new int[1];
            GLES31.glGenTextures(1, textures, 0);
            GlUtil.checkGlError("glGenTextures");

            int texId = textures[0];
            GLES31.glBindTexture(textureTarget, texId);
            GlUtil.checkGlError("glBindTexture " + texId);

            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, format, width, height);
            GlUtil.checkGlError("glTexStorage2D");

            GLES31.glTexParameterf(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
            GLES31.glTexParameterf(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T,
                                   GLES31.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            return texId;
        }

        /**
         */
        private long runTextureTest(WindowSurface windowSurface) {
            long totalTime = 0;

            // Prep GL/EGL.  We use an identity projection matrix, which means the surface
            // coordinates span from -1 to 1 in both dimensions.
            windowSurface.makeCurrent();

            GLES31.glClearColor(1f, 0f, 0f, 1f);
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);

            mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
            Drawable2d rectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);
            mRect = new Sprite2d(rectDrawable);
            mTextureHandle = loadImageIntoTexture(mImageFile);

            mRect.setScale(1f, 1f);
            mRect.setTexture(mTextureHandle);

            Log.d(TAG, "rect draw...");
            mRect.draw(mTexProgram, GlUtil.IDENTITY_MATRIX);

            GLES31.glFinish();

            GLES31.glDeleteTextures(1, new int[]{ mTextureHandle }, 0);
            mTextureHandle = -1;

            Log.d(TAG, "swapBuffers...");
            windowSurface.swapBuffers();

            return totalTime;
        }
    }
}
