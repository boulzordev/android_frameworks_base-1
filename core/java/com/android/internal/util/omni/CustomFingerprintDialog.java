/*
 * Copyright (C) 2012 The CyanogenMod Project
 * Copyright (C) 2015-2018 The OmniROM Project
 *
 * Licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.android.internal.util.omni;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

public interface CustomFingerprintDialog {

    public void setBundle(Bundle bundle);
    public WindowManager.LayoutParams getLayoutParams();
    public void showHelpMessage(String message);
    public void showErrorMessage(String error);
    public void startDismiss();
    public void forceRemove();
    public void resetMessage();
    public View getView();
}
