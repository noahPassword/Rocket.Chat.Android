package chat.rocket.android.service;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import bolts.Continuation;
import bolts.Task;
import bolts.TaskCompletionSource;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.helper.TextUtils;
import chat.rocket.android.model.ServerConfig;
import chat.rocket.android.model.internal.Session;
import chat.rocket.android.realm_helper.RealmHelper;
import chat.rocket.android.realm_helper.RealmStore;
import chat.rocket.android.service.ddp.ActiveUsersSubscriber;
import chat.rocket.android.service.ddp.LoginServiceConfigurationSubscriber;
import chat.rocket.android.service.observer.LoadMessageProcedureObserver;
import chat.rocket.android.service.observer.MethodCallObserver;
import chat.rocket.android.service.observer.SessionObserver;
import chat.rocket.android.service.observer.TokenLoginObserver;
import chat.rocket.android.api.RocketChatWebSocketAPI;
import chat.rocket.android_ddp.DDPClientCallback;
import hugo.weaving.DebugLog;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import org.json.JSONObject;
import timber.log.Timber;

/**
 * Thread for handling WebSocket connection.
 */
public class RocketChatWebSocketThread extends HandlerThread {
  private static final Class[] REGISTERABLE_CLASSES = {
      LoginServiceConfigurationSubscriber.class,
      ActiveUsersSubscriber.class,
      TokenLoginObserver.class,
      MethodCallObserver.class,
      SessionObserver.class,
      LoadMessageProcedureObserver.class
  };
  private final Context appContext;
  private final String serverConfigId;
  private final RealmHelper defaultRealm;
  private final RealmHelper serverConfigRealm;
  private final ArrayList<Registerable> listeners = new ArrayList<>();
  private RocketChatWebSocketAPI webSocketAPI;
  private boolean socketExists;
  private boolean listenersRegistered;

  private RocketChatWebSocketThread(Context appContext, String serverConfigId) {
    super("RC_thread_" + serverConfigId);
    this.appContext = appContext;
    this.serverConfigId = serverConfigId;
    defaultRealm = RealmStore.getDefault();
    serverConfigRealm = RealmStore.getOrCreate(serverConfigId);
  }

  /**
   * create new Thread.
   */
  @DebugLog public static Task<RocketChatWebSocketThread> getStarted(Context appContext,
      ServerConfig config) {
    TaskCompletionSource<RocketChatWebSocketThread> task = new TaskCompletionSource<>();
    new RocketChatWebSocketThread(appContext, config.getServerConfigId()) {
      @Override protected void onLooperPrepared() {
        try {
          super.onLooperPrepared();
          task.setResult(this);
        } catch (Exception exception) {
          task.setError(exception);
        }
      }
    }.start();
    return task.getTask()
        .onSuccessTask(_task ->
            _task.getResult().connect().onSuccessTask(__task -> _task));
  }

  @Override protected void onLooperPrepared() {
    super.onLooperPrepared();
    forceInvalidateTokens();
  }

  private void forceInvalidateTokens() {
    serverConfigRealm.executeTransaction(realm -> {
      Session session = realm.where(Session.class)
          .equalTo("sessionId", Session.DEFAULT_ID).findFirst();
      if (session != null
          && !TextUtils.isEmpty(session.getToken())
          && (session.isTokenVerified() || !TextUtils.isEmpty(session.getError()))) {
        session.setTokenVerified(false);
        session.setError(null);
      }
      return null;
    }).continueWith(new LogcatIfError());
  }

  /**
   * destroy the thread.
   */
  @DebugLog public static void destroy(RocketChatWebSocketThread thread) {
    thread.quit();
  }

  @Override public boolean quit() {
    if (isAlive()) {
      new Handler(getLooper()).post(() -> {
        Timber.d("thread %s: quit()", Thread.currentThread().getId());
        unregisterListeners();
        RocketChatWebSocketThread.super.quit();
      });
      return true;
    } else {
      return super.quit();
    }
  }

  private Task<Void> ensureConnection() {
    if (webSocketAPI == null || !webSocketAPI.isConnected()) {
      return connect();
    } else {
      return Task.forResult(null);
    }
  }

  /**
   * synchronize the state of the thread with ServerConfig.
   */
  @DebugLog public void keepalive() {
    ensureConnection().continueWith(task -> {
      new Handler(getLooper()).post(this::keepaliveListeners);
      return null;
    });
  }

  private void prepareWebSocket(String hostname) {
    if (webSocketAPI == null || !webSocketAPI.isConnected()) {
      webSocketAPI = RocketChatWebSocketAPI.create(hostname);
    }
  }

  @DebugLog private Task<Void> connect() {
    if (socketExists) {
      return Task.forResult(null);
    }
    socketExists = true;

    final ServerConfig config = defaultRealm.executeTransactionForRead(realm ->
        realm.where(ServerConfig.class).equalTo("serverConfigId", serverConfigId).findFirst());

    prepareWebSocket(config.getHostname());
    return webSocketAPI.connect(config.getSession()).onSuccessTask(task -> {
      final String session = task.getResult().session;
      defaultRealm.executeTransaction(realm ->
          realm.createOrUpdateObjectFromJson(ServerConfig.class, new JSONObject()
              .put("serverConfigId", serverConfigId)
              .put("session", session))
      ).onSuccess(_task -> serverConfigRealm.executeTransaction(realm -> {
        Session sessionObj = realm.where(Session.class)
            .equalTo("sessionId", Session.DEFAULT_ID)
            .findFirst();

        if (sessionObj == null) {
          realm.createOrUpdateObjectFromJson(Session.class,
              new JSONObject().put("sessionId", Session.DEFAULT_ID));
        }
        return null;
      })).continueWith(new LogcatIfError());
      return task;
    }).onSuccess(new Continuation<DDPClientCallback.Connect, Object>() {
      // TODO type detection doesn't work due to retrolambda's bug...
      @Override public Object then(Task<DDPClientCallback.Connect> task)
          throws Exception {
        registerListeners();

        // handling WebSocket#onClose() callback.
        task.getResult().client.getOnCloseCallback().onSuccess(_task -> {
          quit();
          return null;
        }).continueWith(_task -> {
          if (_task.isFaulted()) {
            ServerConfig.logConnectionError(serverConfigId, _task.getError());
          }
          return null;
        });

        return null;
      }
    }).continueWith(task -> {
      if (task.isFaulted()) {
        ServerConfig.logConnectionError(serverConfigId, task.getError());
      }
      return null;
    });
  }

  //@DebugLog
  private void registerListeners() {
    if (!Thread.currentThread().getName().equals("RC_thread_" + serverConfigId)) {
      // execute in Looper.
      new Handler(getLooper()).post(() -> {
        registerListeners();
      });
      return;
    }

    if (listenersRegistered) {
      return;
    }
    listenersRegistered = true;

    for (Class clazz : REGISTERABLE_CLASSES) {
      try {
        Constructor ctor = clazz.getConstructor(Context.class, RealmHelper.class,
            RocketChatWebSocketAPI.class);
        Object obj = ctor.newInstance(appContext, serverConfigRealm, webSocketAPI);

        if (obj instanceof Registerable) {
          Registerable registerable = (Registerable) obj;
          registerable.register();
          listeners.add(registerable);
        }
      } catch (Exception exception) {
        Timber.w(exception, "Failed to register listeners!!");
      }
    }
  }

  //@DebugLog
  private void keepaliveListeners() {
    if (!socketExists || !listenersRegistered) {
      return;
    }

    for (Registerable registerable : listeners) {
      registerable.keepalive();
    }
  }

  @DebugLog
  private void unregisterListeners() {
    if (!socketExists || !listenersRegistered) {
      return;
    }

    Iterator<Registerable> iterator = listeners.iterator();
    while (iterator.hasNext()) {
      Registerable registerable = iterator.next();
      registerable.unregister();
      iterator.remove();
    }
    if (webSocketAPI != null) {
      webSocketAPI.close();
      webSocketAPI = null;
    }
    listenersRegistered = false;
    socketExists = false;
  }
}