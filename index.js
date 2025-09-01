import { NativeModules } from 'react-native';

const { SecondaryScreen } = NativeModules || {};

function assertModule() {
  if (!SecondaryScreen) {
    throw new Error('Native module "SecondaryScreen" is not linked. Make sure to install and autolink or register the package.');
  }
}

export async function show(componentName) {
  assertModule();
  // 返回 promise
  return SecondaryScreen.show(componentName);
}

export async function dismiss() {
  assertModule();
  return SecondaryScreen.dismiss();
}

export default {
  show,
  dismiss,
};
