package com.tvlearning.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String reason;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            reason = "boot";
        } else if ("android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            reason = "quickboot";
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            reason = "update";
        } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            reason = "unlock";
        } else {
            return;
        }
        WatchPolicyService.recover(context, reason);
    }
}
