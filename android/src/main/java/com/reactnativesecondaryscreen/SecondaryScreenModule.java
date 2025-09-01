package com.reactnativesecondaryscreen;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactNativeHost;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.ReactRootView;

public class SecondaryScreenModule extends ReactContextBaseJavaModule {
  private static final String TAG = "SecondaryScreenModule";
  private @Nullable Presentation presentation;
  private @Nullable ReactRootView reactRootView;
  private @Nullable DisplayManager.DisplayListener displayListener;
  private @Nullable String currentComponentName;
  private boolean isReactAppStarted = false;

  public SecondaryScreenModule(@NonNull ReactApplicationContext reactContext) {
    super(reactContext);
    Log.d(TAG, "初始化 SecondaryScreenModule");
  }

  @NonNull
  @Override
  public String getName() {
    return "SecondaryScreen";
  }

  @ReactMethod
  public void show(final String componentName, com.facebook.react.bridge.Promise promise) {
    Log.d(TAG, "调用 show 方法，组件名: " + componentName);

    if (componentName == null || componentName.trim().isEmpty()) {
      promise.reject("INVALID_COMPONENT", "组件名不能为空");
      return;
    }

    UiThreadUtil.runOnUiThread(() -> {
      try {
        ReactApplicationContext context = getReactApplicationContext();
        if (context == null) {
          promise.reject("CONTEXT_ERROR", "ReactApplicationContext 为空");
          return;
        }

        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
          promise.reject("DISPLAY_MANAGER_ERROR", "DisplayManager 不可用");
          return;
        }

        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
          Log.e(TAG, "没有可用的副屏");
          promise.reject("NO_DISPLAY", "没有可用副屏");
          return;
        }

        // 先清理旧的
        dismissInternal();

        Display display = displays[0];
        Log.d(TAG, "找到副屏，ID: " + display.getDisplayId());

        currentComponentName = componentName;

        ReactApplication app = (ReactApplication) context.getApplicationContext();
        if (app == null) {
          promise.reject("APP_ERROR", "ReactApplication 不可用");
          return;
        }

        ReactNativeHost host = app.getReactNativeHost();
        if (host == null) {
          promise.reject("HOST_ERROR", "ReactNativeHost 不可用");
          return;
        }

        ReactInstanceManager rim = host.getReactInstanceManager();
        if (rim == null) {
          promise.reject("RIM_ERROR", "ReactInstanceManager 不可用");
          return;
        }

        if (!rim.hasStartedCreatingInitialContext()) {
          Log.d(TAG, "ReactInstanceManager 尚未初始化，正在后台创建");
          rim.createReactContextInBackground();
        }

        presentation = new Presentation(context, display) {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
              super.onCreate(savedInstanceState);
              Log.d(TAG, "Presentation onCreate 被调用");

              try {
                Activity currentActivity = context.getCurrentActivity();
                Context rootViewContext = currentActivity != null ? currentActivity : context;

                reactRootView = new ReactRootView(rootViewContext);

                // 设置背景色便于调试
                reactRootView.setBackgroundColor(0xFF00FF00);

                setContentView(reactRootView);
                isReactAppStarted = false;

                reactRootView.postDelayed(() -> {
                  try {
                    if (reactRootView != null && !isReactAppStarted) {
                      ReactContext currentContext = rim.getCurrentReactContext();
                      if (currentContext == null) {
                        UiThreadUtil.runOnUiThread(() -> {
                          android.widget.TextView errorView = new android.widget.TextView(rootViewContext);
                          errorView.setText("React 上下文未就绪");
                          errorView.setTextColor(0xFFFFFFFF);
                          errorView.setBackgroundColor(0xFFFF0000);
                          errorView.setGravity(android.view.Gravity.CENTER);
                          setContentView(errorView);
                        });
                        return;
                      }

                      Bundle initialProps = new Bundle();
                      initialProps.putString("screen", "secondary");
                      initialProps.putLong("timestamp", System.currentTimeMillis());

                      reactRootView.setBackgroundColor(0x00000000);

                      try {
                        reactRootView.startReactApplication(rim, componentName, initialProps);
                        isReactAppStarted = true;
                        Log.d(TAG, "React 应用启动完成: " + componentName);

                        TextView testView = new TextView(getReactApplicationContext());
                        testView.setText("测试文本 - React 组件应在这里显示");
                        testView.setTextColor(Color.WHITE);
                        testView.setTextSize(20);
                        testView.setGravity(Gravity.CENTER);
                        testView.setBackgroundColor(Color.BLUE);
                        testView.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 200));

                        LinearLayout container = new LinearLayout(getReactApplicationContext());
                        container.setOrientation(LinearLayout.VERTICAL);
                        container.setBackgroundColor(Color.GRAY);

                        if (reactRootView.getParent() != null) {
                            ((ViewGroup) reactRootView.getParent()).removeView(reactRootView);
                        }

                        container.addView(testView);
                        container.addView(reactRootView);

                        setContentView(container);

                        reactRootView.post(() -> {
                          if (reactRootView != null) {
                            if (reactRootView.getChildCount() == 0) {
                              reactRootView.setBackgroundColor(0xFF00FF00);
                            }
                          }
                        });
                      } catch (Exception startException) {
                        Log.e(TAG, "启动 React 应用时出现异常", startException);
                        android.widget.TextView errorView = new android.widget.TextView(rootViewContext);
                        errorView.setText("启动失败: " + startException.getMessage());
                        errorView.setTextColor(0xFFFFFFFF);
                        errorView.setBackgroundColor(0xFFFF0000);
                        errorView.setGravity(android.view.Gravity.CENTER);
                        errorView.setTextSize(12);
                        setContentView(errorView);
                      }

                      reactRootView.postDelayed(() -> {
                        if (reactRootView != null && reactRootView.getChildCount() == 0) {
                          try {
                            reactRootView.unmountReactApplication();
                            reactRootView.startReactApplication(rim, componentName, initialProps);
                          } catch (Exception reloadException) {
                            Log.e(TAG, "强制重新加载失败", reloadException);
                          }
                        }
                      }, 3000);
                    }
                  } catch (Exception e) {
                    Log.e(TAG, "启动 React 应用时发生异常", e);
                    UiThreadUtil.runOnUiThread(() -> {
                      if (reactRootView != null) {
                        android.widget.TextView errorView = new android.widget.TextView(rootViewContext);
                        errorView.setText("启动副屏失败: " + e.getMessage());
                        errorView.setTextColor(0xFFFFFFFF);
                        errorView.setBackgroundColor(0xFFFF0000);
                        errorView.setGravity(android.view.Gravity.CENTER);
                        setContentView(errorView);
                      }
                    });
                  }
                }, 1000);

              } catch (Exception e) {
                Log.e(TAG, "Presentation onCreate 中出错", e);
              }
            }

            @Override
            public void onStop() {
              super.onStop();
              UiThreadUtil.runOnUiThread(() -> {
                try {
                  if (reactRootView != null) {
                    reactRootView.unmountReactApplication();
                    reactRootView = null;
                    isReactAppStarted = false;
                  }
                } catch (Exception e) {
                  Log.e(TAG, "清理 ReactRootView 时出错", e);
                }
              });
            }
          };

        presentation.setOnDismissListener(dialog -> UiThreadUtil.runOnUiThread(() -> dismissInternal()));

        WindowManager.LayoutParams params = presentation.getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        presentation.getWindow().setAttributes(params);

        presentation.show();
        Log.d(TAG, "Presentation 显示成功");

        displayListener = new DisplayManager.DisplayListener() {
          @Override
          public void onDisplayAdded(int displayId) {
            Log.d(TAG, "副屏添加: " + displayId);
          }

          @Override
          public void onDisplayChanged(int displayId) {
            Log.d(TAG, "副屏变化: " + displayId);
          }

          @Override
          public void onDisplayRemoved(int displayId) {
            Log.d(TAG, "副屏移除: " + displayId);
            if (presentation != null && presentation.getDisplay() != null
                && presentation.getDisplay().getDisplayId() == displayId) {
              UiThreadUtil.runOnUiThread(() -> dismissInternal());
            }
          }
        };
        dm.registerDisplayListener(displayListener, null);

        promise.resolve("副屏显示成功");
      } catch (Exception e) {
        Log.e(TAG, "显示副屏时出错", e);
        dismissInternal();
        promise.reject("SHOW_ERROR", "显示副屏时出错: " + e.getMessage(), e);
      }
    });
  }

  @ReactMethod
  public void dismiss() {
    UiThreadUtil.runOnUiThread(this::dismissInternal);
  }

  private void dismissInternal() {
    try {
      ReactApplicationContext context = getReactApplicationContext();

      DisplayManager dm = null;
      if (context != null) {
        dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
      }

      if (presentation != null) {
        try {
          if (presentation.isShowing()) {
            presentation.dismiss();
          }
        } catch (Exception e) {
          Log.e(TAG, "关闭 Presentation 时出错", e);
        } finally {
          presentation = null;
        }
      }

      if (reactRootView != null) {
        try {
          if (reactRootView.getParent() instanceof ViewGroup) {
            ((ViewGroup) reactRootView.getParent()).removeView(reactRootView);
          }
          reactRootView.unmountReactApplication();
        } catch (Exception e) {
          Log.e(TAG, "释放 ReactRootView 时出错", e);
        } finally {
          reactRootView = null;
        }
      }

      if (displayListener != null && dm != null) {
        try {
          dm.unregisterDisplayListener(displayListener);
        } catch (Exception e) {
          Log.e(TAG, "注销 DisplayListener 时出错", e);
        } finally {
          displayListener = null;
        }
      }

      currentComponentName = null;
      isReactAppStarted = false;
    } catch (Exception e) {
      Log.e(TAG, "dismissInternal 执行过程中出现异常", e);
    }
  }
}
