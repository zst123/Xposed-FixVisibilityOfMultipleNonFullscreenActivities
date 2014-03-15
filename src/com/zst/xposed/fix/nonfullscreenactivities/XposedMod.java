package com.zst.xposed.fix.nonfullscreenactivities;

import static de.robv.android.xposed.XposedHelpers.findClass;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedMod implements IXposedHookLoadPackage{
	
	/* Fixes homescreen going to the front when an app is launched from the homescreen
	 * while a floating window is already open.
	 * 
	 * Based on Google's commit:
	 * 
	 * 		Fix visibility of multiple non-fullscreen activities.
	 * 
	 * 		Issue detail:
	 * 			Assume X, Y are non-fullscreen activities.
	 * 			a.Home starts an activity X in task A in application stack.
	 * 			b.X starts an activity Y in <task A> or <new task B>
	 * 			c.Activity X will be invisible.
	 * 
	 * 		How to fix:
	 * 			Because the function "isActivityOverHome" means an activity is able to
	 * 			see home. 
	 * 			But there may have many non-fullscreen activities between the top
	 * 			non-fullscreen activity and home.
	 * 			If flag "behindFullscreen" is set, those middle activities will be
	 * 			invisible.
	 * 			So it should only take care from who is adjacent to home.
	 * 			Then check two flags frontOfTask(task root) and mOnTopOfHome for
	 * 			constraining the condition.
	 *
	 * https://github.com/android/platform_frameworks_base/commit/446ef1de8d373c1b017df8d19ebf9a47811fb402
	 */
	
	private static boolean mIsInMethod;
	private static Object mCurrentActivityRecord;
	
	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("android")) return;
		fixKitKatMovingHomeFrontBug(lpparam);
	}
	
	private static void fixKitKatMovingHomeFrontBug(final LoadPackageParam lp) throws Throwable {
		final Class<?> activityStack = findClass("com.android.server.am.ActivityStack",
				lp.classLoader);
		
		XposedBridge.hookAllMethods(activityStack, "ensureActivitiesVisibleLocked",
				new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				mCurrentActivityRecord = null;
				mIsInMethod = true;
			}
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				mIsInMethod = false;
			}
		});
		XposedBridge.hookAllMethods(activityStack, "isActivityOverHome", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				mCurrentActivityRecord = param.args[0];
				// This method is called directly before "isHomeStack"
				// We make use of this method to retrieve our ActivityRecord instance.
			}
		});
		XposedBridge.hookAllMethods(activityStack, "isHomeStack", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				if (mIsInMethod == false) return;
				// We are not in "ensureActivitiesVisibleLocked"
				// Don't continue to avoid messing up the system
				
				if (mCurrentActivityRecord == null) return;
				
				final boolean is_home_stack = (Boolean) param.getResult();
				if (is_home_stack) return;
				// home stack, so why bother continuing since it will skip these values anyway.
				
				final Object activity_record = mCurrentActivityRecord;
				// Transfer the value so if this method is called twice,
				// the other ActivityRecord will not override this object
				
				final boolean front_of_task = (Boolean) XposedHelpers.findField(activity_record.getClass(),
						"frontOfTask").get(activity_record);
				Object task = XposedHelpers.findField(activity_record.getClass(), "task").get(activity_record);
				final boolean top_of_home = (Boolean) XposedHelpers.findField(task.getClass(),
						"mOnTopOfHome").get(task);
				
				param.setResult(!(!is_home_stack && front_of_task && top_of_home));
				
				mCurrentActivityRecord = null;
			}
		});
	}
}
