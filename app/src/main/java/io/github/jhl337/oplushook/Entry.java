package io.github.jhl337.oplushook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam param) throws Throwable {
        try {
            String pn = param.packageName;
            ClassLoader cl = param.classLoader;
            switch (pn) {
                case "android":
                    XposedBridge.log("OplusHook: Initializing...");
                    hookActivityTaskManagerService(cl);
                    hookOplusZoomWindow(cl);
                    XposedBridge.log("OplusHook: Initialized hooks.");
                    break;
                case "com.oplus.screenshot":
					hookScreenshotHardwareBuffer(cl);
                    hookOplusScreenCapture(cl);
					break;
                case "com.oplus.appplatform":
                    hookScreenshotHardwareBuffer(cl);
                    hookScreenCapture(cl);
					break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookActivityTaskManagerService(ClassLoader cl) {
        try {
            Class<?> atmsClazz = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", cl);
            Class<?> binderClazz = XposedHelpers.findClass("android.os.IBinder", cl);
            Class<?> observerClazz = XposedHelpers.findClass("android.app.IScreenCaptureObserver", cl);
            XposedHelpers.findAndHookMethod(
                    atmsClazz,
                    "registerScreenCaptureObserver",
                    binderClazz,
                    observerClazz,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }
    
    private void hookScreenCapture(ClassLoader cl) {
        try {
            Class<?> scClazz = XposedHelpers.findClass("android.window.ScreenCapture", cl);
            Class<?> argsClazz = XposedHelpers.findClass("android.window.ScreenCapture$CaptureArgs", cl);

            final Field captureSecureLayersField = argsClazz.getDeclaredField("mCaptureSecureLayers");
            captureSecureLayersField.setAccessible(true);

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object captureArgs = param.args[0];
                    if (captureArgs != null) {
                        try {
                            captureSecureLayersField.set(captureArgs, true);
                        } catch (IllegalAccessException ignored) {
                        }
                    }
                }
            };

            for (Method m : scClazz.getDeclaredMethods()) {
                String name = m.getName();
                if ("nativeCaptureDisplay".equals(name) || "nativeCaptureLayers".equals(name)) {
                    XposedBridge.hookMethod(m, hook);
                }
            }

        } catch (Throwable ignored) {
        }
    }

    private void hookScreenshotHardwareBuffer(ClassLoader cl) {
        try {
            Class<?> clazz = XposedHelpers.findClass("android.window.ScreenCapture$ScreenshotHardwareBuffer", cl);
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "containsSecureLayers",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(false);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    private void hookOplusScreenCapture(ClassLoader cl) {
        try {
            Class<?> clazz = XposedHelpers.findClass(
                    "com.oplus.screenshot.OplusScreenCapture$CaptureArgs$Builder", cl);
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "setUid",
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = -1L;
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    private void hookOplusZoomWindow(final ClassLoader cl) {
        try {
            Class<?> transactionClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.WindowState",
                    cl,
                    "updateSurfacePosition",
                    transactionClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object winState = param.thisObject;
                            boolean isNeedHide = checkWindowForHide(winState);
                            Object transaction = param.args[0];
                            Object surfaceControl = XposedHelpers.getObjectField(winState, "mSurfaceControl");
                            if (surfaceControl != null) {
                                XposedHelpers.callMethod(transaction, "setSkipScreenshot", surfaceControl, isNeedHide);
                            }

                            Object task = XposedHelpers.callMethod(winState, "getTask");
                            if (task != null) {
                                Object taskSurface = XposedHelpers.callMethod(task, "getSurfaceControl");
                                if (taskSurface != null) {
                                    XposedHelpers.callMethod(transaction, "setSkipScreenshot", taskSurface, isNeedHide);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private boolean checkWindowForHide(Object winState) {
        try {
            String tag = String.valueOf(XposedHelpers.callMethod(winState, "getWindowTag"));
            String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage");
            if ("com.oplus.screenshot/LongshotCapture".equals(tag)
                    || tag.contains("OplusOSZoomFloatHandleView")
                    || "InputMethod".equals(tag)
                    || "com.oplus.appplatform".equals(pkg)
                    || "com.coloros.smartsidebar".equals(pkg)) {
                return true;
            }
            try {
                Object ext = XposedHelpers.getObjectField(winState, "mWindowStateExt");
                if (ext != null) {
                    int mode = (int) XposedHelpers.callMethod(winState, "getWindowingMode");
                    if ((boolean)XposedHelpers.callMethod(ext, "checkIfWindowingModeZoom", mode)) return true;
                }
            } catch (Throwable ignored) {
            }
            Object task = XposedHelpers.callMethod(winState, "getTask");
            if (task == null) return false;
            Object rootTask = XposedHelpers.callMethod(task, "getRootTask");
            if (rootTask == null) rootTask = task;
            if (!isFlexibleTaskAndHasCaption(rootTask)) return false;
            Object wrapper = XposedHelpers.callMethod(rootTask, "getWrapper");
            if (wrapper == null) return false;
            Object extImpl = XposedHelpers.callMethod(wrapper, "getExtImpl");
            if (extImpl == null) return false;
            int flexibleZoomState = (int) XposedHelpers.callMethod(extImpl, "getFlexibleZoomState");
            if (flexibleZoomState != 0) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isFlexibleTaskAndHasCaption(Object rootTask) {
        ClassLoader cl = rootTask.getClass().getClassLoader();

        boolean isFlexibleTask = false;
        try {
            Class<?> flexUtilsClass =
                    XposedHelpers.findClass("com.android.server.wm.FlexibleWindowUtils", cl);
            isFlexibleTask = (boolean) XposedHelpers.callStaticMethod(
                    flexUtilsClass,
                    "isFlexibleTaskAndHasCaption",
                    rootTask
            );
        } catch (Throwable ignored) {
        }
        return isFlexibleTask;
    }
}
