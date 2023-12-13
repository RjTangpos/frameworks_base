/*
 * Copyright (C) 2019 The PixelExperience Project
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

package com.android.server.policy;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

public class SwipeToScreenshotListener implements PointerEventListener {
    private static final String TAG = "SwipeToScreenshotListener";
    private static final int THREE_GESTURE_STATE_NONE = 0;
    private static final int THREE_GESTURE_STATE_DETECTING = 1;
    private static final int THREE_GESTURE_STATE_DETECTED_FALSE = 2;
    private static final int THREE_GESTURE_STATE_DETECTED_TRUE = 3;
    private static final int THREE_GESTURE_STATE_NO_DETECT = 4;

    private boolean mBootCompleted;
    private boolean mDeviceProvisioned = false;
    private final Context mContext;
    private final Callbacks mCallbacks;
    private final float[] mInitMotionY = new float[3];
    private final int[] mPointerIds = new int[3];
    private int mThreeGestureState = THREE_GESTURE_STATE_NONE;
    private final int mThreeGestureThreshold;
    private final int mThreshold;
    private final DisplayMetrics mDisplayMetrics;

    public SwipeToScreenshotListener(Context context, Callbacks callbacks) {
        mContext = context;
        mCallbacks = callbacks;
        mDisplayMetrics = mContext.getResources().getDisplayMetrics();
        mThreshold = (int) (50.0f * mDisplayMetrics.density);
        mThreeGestureThreshold = mThreshold * 3;
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (!checkInitializationAndProvisioning()) {
            return;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            changeThreeGestureState(THREE_GESTURE_STATE_NONE);
        } else if (mThreeGestureState == THREE_GESTURE_STATE_NONE && event.getPointerCount() == 3) {
            processStartThreeGesture(event);
        }

        if (mThreeGestureState == THREE_GESTURE_STATE_DETECTING && event.getPointerCount() == 3 &&
                event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            processThreeGestureMove(event);
        }
    }

    private boolean checkInitializationAndProvisioning() {
        if (!mBootCompleted && (mBootCompleted = SystemProperties.getBoolean("sys.boot_completed", false)))
            return false;

        if (!mDeviceProvisioned && (mDeviceProvisioned = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0))
            return false;

        return true;
    }

    private void processStartThreeGesture(MotionEvent event) {
        if (checkIsStartThreeGesture(event)) {
            changeThreeGestureState(THREE_GESTURE_STATE_DETECTING);
            recordInitialMotion(event);
        } else {
            changeThreeGestureState(THREE_GESTURE_STATE_NO_DETECT);
        }
    }

    private void recordInitialMotion(MotionEvent event) {
        for (int i = 0; i < 3; i++) {
            mPointerIds[i] = event.getPointerId(i);
            mInitMotionY[i] = event.getY(i);
        }
    }

    private void processThreeGestureMove(MotionEvent event) {
        float distance = calculateDistance(event);
        if (distance >= mThreeGestureThreshold) {
            changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_TRUE);
            mCallbacks.onSwipeThreeFinger();
        }
    }

    private float calculateDistance(MotionEvent event) {
        float distance = 0.0f;
        for (int i = 0; i < 3; i++) {
            int index = event.findPointerIndex(mPointerIds[i]);
            if (index < 0 || index >= 3) {
                changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_FALSE);
                return 0;
            }
            distance += event.getY(index) - mInitMotionY[i];
        }
        return distance;
    }

    private void changeThreeGestureState(int state) {
        if (mThreeGestureState != state) {
            mThreeGestureState = state;
            setScreenshotSystemProperty(mThreeGestureState == THREE_GESTURE_STATE_DETECTED_TRUE ||
                    mThreeGestureState == THREE_GESTURE_STATE_DETECTING);
        }
    }

    private void setScreenshotSystemProperty(boolean enable) {
        try {
            SystemProperties.set("sys.android.screenshot", enable ? "true" : "false");
        } catch (Exception e) {
            Log.e(TAG, "Exception when setprop", e);
        }
    }

    private boolean checkIsStartThreeGesture(MotionEvent event) {
        if (event.getEventTime() - event.getDownTime() > 500) {
            return false;
        }
        int height = mDisplayMetrics.heightPixels;
        int width = mDisplayMetrics.widthPixels;
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE, minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (int i = 0; i < event.getPointerCount(); i++) {
            float x = event.getX(i);
            float y = event.getY(i);
            if (y > (height - mThreshold)) {
                return false;
            }
            maxX = Math.max(maxX, x);
            minX = Math.min(minX, x);
            maxY = Math.max(maxY, y);
            minY = Math.min(minY, y);
        }
        return maxY - minY <= mDisplayMetrics.density * 150.0f && maxX - minX <= Math.min(width, height);
    }

    interface Callbacks {
        void onSwipeThreeFinger();
    }
}
