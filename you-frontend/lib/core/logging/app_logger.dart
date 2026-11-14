import 'package:talker/talker.dart';

/// Register stable module.action.outcome identifiers here.
enum AppLogEvent {
  appInitializationSucceeded('app.initialization.succeeded'),
  appInitializationFailed('app.initialization.failed'),
  appFrameworkFailed('app.framework.failed'),
  appRuntimeFailed('app.runtime.failed'),
  authLoginStarted('auth.login.started'),
  authLoginSucceeded('auth.login.succeeded'),
  authLoginFailed('auth.login.failed'),
  authRegisterStarted('auth.register.started'),
  authRegisterSucceeded('auth.register.succeeded'),
  authRegisterFailed('auth.register.failed'),
  networkRequestFailed('network.request.failed'),
  authSessionExpired('auth.session.expired'),
  storageUploadStarted('storage.upload.started'),
  storageUploadSucceeded('storage.upload.succeeded'),
  userProfileReadStarted('user.read_profile.started'),
  userProfileReadSucceeded('user.read_profile.succeeded'),
  userProfileReadFailed('user.read_profile.failed'),
  userProfileUpdateStarted('user.update_profile.started'),
  userProfileUpdateSucceeded('user.update_profile.succeeded'),
  userProfileUpdateFailed('user.update_profile.failed'),
  userAvatarReadStarted('user.read_avatar.started'),
  userAvatarReadSucceeded('user.read_avatar.succeeded'),
  userAvatarReadFailed('user.read_avatar.failed'),
  userAvatarSelectStarted('user.select_avatar.started'),
  userAvatarSelectSucceeded('user.select_avatar.succeeded'),
  userAvatarSelectFailed('user.select_avatar.failed'),
  authLogoutStarted('auth.logout.started'),
  authLogoutSucceeded('auth.logout.succeeded'),
  authLogoutFailed('auth.logout.failed'),
  authSessionRestoreStarted('auth.restore_session.started'),
  authSessionRestoreSucceeded('auth.restore_session.succeeded'),
  authSessionRestoreFailed('auth.restore_session.failed'),
  authSessionPersistStarted('auth.persist_session.started'),
  authSessionPersistSucceeded('auth.persist_session.succeeded'),
  authSessionPersistFailed('auth.persist_session.failed'),
  authSessionClearStarted('auth.clear_session.started'),
  authSessionClearSucceeded('auth.clear_session.succeeded'),
  authSessionClearFailed('auth.clear_session.failed'),
  storageDraftSaveStarted('storage.save_draft.started'),
  storageDraftSaveSucceeded('storage.save_draft.succeeded'),
  storageDraftSaveFailed('storage.save_draft.failed'),
  storageDraftReadStarted('storage.read_drafts.started'),
  storageDraftReadSucceeded('storage.read_drafts.succeeded'),
  storageDraftReadFailed('storage.read_drafts.failed'),
  userAvatarSelectCancelled('user.select_avatar.cancelled'),
  gatheringCreateStarted('gathering.create.started'),
  gatheringCreateSucceeded('gathering.create.succeeded'),
  gatheringCreateFailed('gathering.create.failed'),
  gatheringUpdateStarted('gathering.update.started'),
  gatheringUpdateSucceeded('gathering.update.succeeded'),
  gatheringUpdateFailed('gathering.update.failed'),
  gatheringJoinStarted('gathering.join.started'),
  gatheringJoinSucceeded('gathering.join.succeeded'),
  gatheringJoinFailed('gathering.join.failed'),
  gatheringScanFailed('gathering.scan.failed'),
  gatheringMomentsAddStarted('gathering.add_moments.started'),
  gatheringMomentsAddSucceeded('gathering.add_moments.succeeded'),
  gatheringMomentsAddFailed('gathering.add_moments.failed'),
  gatheringMomentsRemoveStarted('gathering.remove_moments.started'),
  gatheringMomentsRemoveSucceeded('gathering.remove_moments.succeeded'),
  gatheringMomentsRemoveFailed('gathering.remove_moments.failed'),
  gatheringLeaveStarted('gathering.leave.started'),
  gatheringLeaveSucceeded('gathering.leave.succeeded'),
  gatheringLeaveFailed('gathering.leave.failed'),
  gatheringEndStarted('gathering.end.started'),
  gatheringEndSucceeded('gathering.end.succeeded'),
  gatheringEndFailed('gathering.end.failed'),
  gatheringDissolveStarted('gathering.dissolve.started'),
  gatheringDissolveSucceeded('gathering.dissolve.succeeded'),
  gatheringDissolveFailed('gathering.dissolve.failed'),
  gatheringFaceOpenStarted('gathering.face_open.started'),
  gatheringFaceOpenSucceeded('gathering.face_open.succeeded'),
  gatheringFaceOpenFailed('gathering.face_open.failed'),
  gatheringFacePreviewStarted('gathering.face_preview.started'),
  gatheringFacePreviewSucceeded('gathering.face_preview.succeeded'),
  gatheringFacePreviewFailed('gathering.face_preview.failed'),
  gatheringFaceJoinStarted('gathering.face_join.started'),
  gatheringFaceJoinSucceeded('gathering.face_join.succeeded'),
  gatheringFaceJoinFailed('gathering.face_join.failed'),
  locationReadStarted('location.read.started'),
  locationReadSucceeded('location.read.succeeded'),
  locationReadFailed('location.read.failed'),
  storageUploadFailed('storage.upload.failed');

  const AppLogEvent(this.id);

  final String id;
}

abstract final class AppLogger {
  static final _talker = Talker();

  static Future<T> track<T>(
    Future<T> Function() action, {
    required AppLogEvent started,
    required AppLogEvent succeeded,
    required AppLogEvent failed,
  }) async {
    debug(started);
    try {
      final result = await action();
      info(succeeded);
      return result;
    } catch (exception, stackTrace) {
      error(failed, error: exception, stackTrace: stackTrace);
      rethrow;
    }
  }

  static void debug(AppLogEvent event) => _talker.debug(event.id);

  static void info(AppLogEvent event) => _talker.info(event.id);

  static void warning(AppLogEvent event) => _talker.warning(event.id);

  static void error(
    AppLogEvent event, {
    Object? error,
    StackTrace? stackTrace,
  }) => _talker.error(event.id, error?.runtimeType, stackTrace);

  static void fatal(
    AppLogEvent event, {
    Object? error,
    StackTrace? stackTrace,
  }) => _talker.critical('FATAL ${event.id}', error?.runtimeType, stackTrace);
}
