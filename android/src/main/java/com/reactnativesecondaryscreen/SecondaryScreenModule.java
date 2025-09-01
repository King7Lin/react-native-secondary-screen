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

/**
 * SecondaryScreenModule
 *
 * 本模块用于在 Android 的副屏（Presentation）上展示 React Native 组件。
 * 这个文件带有大量注释，用于解释为何之前会出现“副屏白屏”以及当前代码中采取的防护措施。
 *
 * 核心策略：
 *  1. 在启动 React 组件前，确保 ReactInstanceManager/ReactContext 已初始化（否则会白屏）。
 *  2. 使用正确的 Context（优先使用当前 Activity 的 context）来创建 ReactRootView，避免 context 不匹配导致绘制失败。
 *  3. 在 Presentation 中先放入可见的调试/占位视图（debug 背景或测试文本），用于快速区分是 React 未渲染还是 Presentation 本身不可见。
 *  4. 捕获异常并显示错误视图，避免无任何提示的白屏；提供重试逻辑以应对不稳定的首次挂载情况。
 */
public class SecondaryScreenModule extends ReactContextBaseJavaModule {
  private static final String TAG = "SecondaryScreenModule";

  // 当前用于显示副屏内容的 Presentation（代表一个 Window）
  private @Nullable Presentation presentation;

  // ReactRootView 用于承载 React Native 的视图树
  private @Nullable ReactRootView reactRootView;

  // 监听副屏添加/移除事件，确保在副屏被移除时能及时清理
  private @Nullable DisplayManager.DisplayListener displayListener;

  // 当前要展示的 React 组件名（JS 端注册的组件名）
  private @Nullable String currentComponentName;

  // 标记是否已经成功启动了 React 应用，避免重复启动
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

  /**
   * 在副屏上展示指定的 React 组件。
   *
   * 注意：此方法会在 UI 线程执行（通过 UiThreadUtil），因为它创建了 Window/UI 元素。
   * 参数：componentName - JS 端注册的组件名；promise - 用于回调结果到 JS。
   */
  @ReactMethod
  public void show(final String componentName, com.facebook.react.bridge.Promise promise) {
    Log.d(TAG, "调用 show 方法，组件名: " + componentName);

    if (componentName == null || componentName.trim().isEmpty()) {
      promise.reject("INVALID_COMPONENT", "组件名不能为空");
      return;
    }

    // 所有涉及 View/Window 的工作都必须在主线程执行
    UiThreadUtil.runOnUiThread(() -> {
      try {
        ReactApplicationContext context = getReactApplicationContext();
        if (context == null) {
          promise.reject("CONTEXT_ERROR", "ReactApplicationContext 为空");
          return;
        }

        // 获取 DisplayManager，用来查询副屏
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
          promise.reject("DISPLAY_MANAGER_ERROR", "DisplayManager 不可用");
          return;
        }

        // 使用 DISPLAY_CATEGORY_PRESENTATION 获取可用于 Presentation 的显示设备
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
          Log.e(TAG, "没有可用的副屏");
          promise.reject("NO_DISPLAY", "没有可用副屏");
          return;
        }

        // 先清理上一次可能残留的资源，避免状态冲突
        dismissInternal();

        // 当前实现选择第一个可用的副屏。若需支持多副屏，可以改为按 id 或尺寸选择。
        Display display = displays[0];
        Log.d(TAG, "找到副屏，ID: " + display.getDisplayId());

        currentComponentName = componentName;

        // 通过 Application 获取 ReactNativeHost/ReactInstanceManager
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

        // 如果 React 原生引擎尚未启动，则后台触发创建 ReactContext
        // 这一点很重要：若直接 startReactApplication 而 ReactContext 未就绪，会导致白屏。
        if (!rim.hasStartedCreatingInitialContext()) {
          Log.d(TAG, "ReactInstanceManager 尚未初始化，正在后台创建");
          rim.createReactContextInBackground();
        }

        // 新建一个 Presentation（相当于在副屏上新建一个 Window）
        presentation = new Presentation(context, display) {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
              super.onCreate(savedInstanceState);
              Log.d(TAG, "Presentation onCreate 被调用");

              try {
                // 优先使用当前 Activity 的 context，这样 ReactRootView 能与 Activity 生命周期更好地协作
                Activity currentActivity = context.getCurrentActivity();
                Context rootViewContext = currentActivity != null ? currentActivity : context;

                // 用正确的 context 创建 ReactRootView
                reactRootView = new ReactRootView(rootViewContext);

                // DEBUG: 先给 root view 一个可见的背景，帮助快速判断是 React 未渲染还是 Window 本身不可见
                reactRootView.setBackgroundColor(0xFF00FF00);

                // 先把 reactRootView 作为 content，随后在确保 React 已启动后再将其放入容器并添加占位视图
                setContentView(reactRootView);
                isReactAppStarted = false;

                /*
                 * 延迟一段时间再尝试启动 React 应用：
                 * - 给予 ReactInstanceManager 一些时间来创建 ReactContext（如果正在后台创建）
                 * - 在生产环境中可以缩短或移除该延迟，但这里保守等待以提高稳定性
                 */
                reactRootView.postDelayed(() -> {
                  try {
                    if (reactRootView != null && !isReactAppStarted) {
                      // 检查当前 ReactContext 是否就绪
                      ReactContext currentContext = rim.getCurrentReactContext();
                      if (currentContext == null) {
                        // 如果 ReactContext 仍未就绪，显示错误提示视图并返回
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

                      // 传递初始 props 给 React 组件，便于 JS 端根据场景展示不同内容
                      Bundle initialProps = new Bundle();
                      initialProps.putString("screen", "secondary");
                      initialProps.putLong("timestamp", System.currentTimeMillis());

                      // 启动前清除占位背景
                      reactRootView.setBackgroundColor(0x00000000);

                      try {
                        // 启动 React 应用，将 React 组件挂载到 reactRootView
                        reactRootView.startReactApplication(rim, componentName, initialProps);
                        isReactAppStarted = true;
                        Log.d(TAG, "React 应用启动完成: " + componentName);

                        // 添加一个用于调试的原生文本视图，便于确认 Presentation 能正确绘制
                        TextView testView = new TextView(getReactApplicationContext());
                        testView.setText("测试文本 - React 组件应在这里显示");
                        testView.setTextColor(Color.WHITE);
                        testView.setTextSize(20);
                        testView.setGravity(Gravity.CENTER);
                        testView.setBackgroundColor(Color.BLUE);
                        testView.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 200));

                        // 将 testView 与 reactRootView 放到一个线性容器中，一方面展示占位，另一方面避免直接替换 content
                        LinearLayout container = new LinearLayout(getReactApplicationContext());
                        container.setOrientation(LinearLayout.VERTICAL);
                        container.setBackgroundColor(Color.GRAY);

                        if (reactRootView.getParent() != null) {
                            ((ViewGroup) reactRootView.getParent()).removeView(reactRootView);
                        }

                        container.addView(testView);
                        container.addView(reactRootView);

                        setContentView(container);

                        // 如果 ReactRootView 仍然没有子 View（可能是 JS 未能挂载），保留绿色背景以便观察
                        reactRootView.post(() -> {
                          if (reactRootView != null) {
                            if (reactRootView.getChildCount() == 0) {
                              // 继续保留绿色背景作为调试标记
                              reactRootView.setBackgroundColor(0xFF00FF00);
                            }
                          }
                        });
                      } catch (Exception startException) {
                        // 启动 React 应用时捕获异常并展示错误视图，避免白屏
                        Log.e(TAG, "启动 React 应用时出现异常", startException);
                        android.widget.TextView errorView = new android.widget.TextView(rootViewContext);
                        errorView.setText("启动失败: " + startException.getMessage());
                        errorView.setTextColor(0xFFFFFFFF);
                        errorView.setBackgroundColor(0xFFFF0000);
                        errorView.setGravity(android.view.Gravity.CENTER);
                        errorView.setTextSize(12);
                        setContentView(errorView);
                      }

                      // 重试逻辑：如果挂载后仍没有子 View，尝试卸载并重新启动一次（应谨慎使用，避免无限重试）
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
                // 捕获 Presentation.onCreate 中的任何异常，记录日志以便排查
                Log.e(TAG, "Presentation onCreate 中出错", e);
              }
            }

            @Override
            public void onStop() {
              super.onStop();
              // Presentation 停止时，确保释放 ReactRootView 以避免内存泄漏或残留状态
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

        // 当 Presentation 被 dismiss 时，进行统一清理
        presentation.setOnDismissListener(dialog -> UiThreadUtil.runOnUiThread(() -> dismissInternal()));

        // 让 Presentation 占满整个显示区域
        WindowManager.LayoutParams params = presentation.getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        presentation.getWindow().setAttributes(params);

        // 显示 Presentation
        presentation.show();
        Log.d(TAG, "Presentation 显示成功");

        // 注册 DisplayListener，监听副屏的添加、变化与移除
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
            // 如果被移除的是当前 Presentation 所在的显示设备，则执行清理
            if (presentation != null && presentation.getDisplay() != null
                && presentation.getDisplay().getDisplayId() == displayId) {
              UiThreadUtil.runOnUiThread(() -> dismissInternal());
            }
          }
        };
        dm.registerDisplayListener(displayListener, null);

        promise.resolve("副屏显示成功");
      } catch (Exception e) {
        // 总体异常捕获：确保在任何异常情况下都能清理资源并把错误返回给 JS
        Log.e(TAG, "显示副屏时出错", e);
        dismissInternal();
        promise.reject("SHOW_ERROR", "显示副屏时出错: " + e.getMessage(), e);
      }
    });
  }

  /**
   * JS 可以调用此方法来关闭副屏
   */
  @ReactMethod
  public void dismiss() {
    UiThreadUtil.runOnUiThread(this::dismissInternal);
  }

  /**
   * 内部统一的清理逻辑：
   * - 关闭并释放 Presentation
   * - 从父视图移除并 unmount ReactRootView
   * - 注销 DisplayListener
   * - 重置内部状态
   */
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
          // 无论是否异常，都把引用设为 null，避免复用已失效的对象
          presentation = null;
        }
      }

      if (reactRootView != null) {
        try {
          if (reactRootView.getParent() instanceof ViewGroup) {
            ((ViewGroup) reactRootView.getParent()).removeView(reactRootView);
          }
          // 卸载 React 应用，释放 JS 端资源与视图树
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
