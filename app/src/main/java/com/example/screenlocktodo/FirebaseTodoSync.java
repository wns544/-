package com.example.screenlocktodo;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FirebaseTodoSync {
    interface Listener {
        void onTodosUpdated();

        void onStatusChanged(String status);

        void onServerStateChanged(int itemCount, long updatedAt);

        void onServerItemsChanged(List<TodoItem> items, long updatedAt);
    }

    private static ListenerRegistration registration;
    private static Listener listener;
    private static String listeningUid;
    private static String lastStatus = "";

    private FirebaseTodoSync() {
    }

    static boolean isConfigured(Context context) {
        return !FirebaseApp.getApps(context.getApplicationContext()).isEmpty()
                && context.getResources().getIdentifier(
                "default_web_client_id",
                "string",
                context.getPackageName()
        ) != 0;
    }

    static FirebaseUser currentUser(Context context) {
        if (!isConfigured(context)) {
            return null;
        }
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    static String webClientId(Context context) {
        int id = context.getResources().getIdentifier(
                "default_web_client_id",
                "string",
                context.getPackageName()
        );
        return id == 0 ? "" : context.getString(id);
    }

    static synchronized void start(Context context, Listener nextListener) {
        listener = nextListener;
        FirebaseUser user = currentUser(context);
        if (user == null) {
            stopRegistration();
            listeningUid = null;
            notifyStatus("signed_out");
            return;
        }
        if (registration != null && user.getUid().equals(listeningUid)) {
            notifyStatus("synced");
            return;
        }

        stopRegistration();
        listeningUid = user.getUid();
        Context appContext = context.getApplicationContext();
        DocumentReference document = documentFor(user.getUid());
        notifyStatus("syncing");

        document.get().addOnSuccessListener(snapshot -> reconcileInitial(appContext, document, snapshot))
                .addOnFailureListener(error -> notifyStatus("offline"));
        registration = document.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                notifyStatus("offline");
                return;
            }
            if (snapshot == null || !snapshot.exists() || snapshot.getMetadata().hasPendingWrites()) {
                return;
            }
            notifyServerSnapshot(snapshot);
            applyRemoteIfNewer(appContext, snapshot);
        });
    }

    static synchronized void stop() {
        stopRegistration();
        listeningUid = null;
        listener = null;
        lastStatus = "";
    }

    static void signOut(Context context) {
        stop();
        if (isConfigured(context)) {
            FirebaseAuth.getInstance().signOut();
        }
    }

    static void onLocalTodosChanged(Context context, List<TodoItem> items, long updatedAt) {
        FirebaseUser user = currentUser(context);
        if (user == null) {
            return;
        }
        upload(documentFor(user.getUid()), user, items, updatedAt);
    }

    private static void reconcileInitial(
            Context context,
            DocumentReference document,
            DocumentSnapshot snapshot
    ) {
        long localUpdatedAt = TodoStore.updatedAt(context);
        long remoteUpdatedAt = remoteUpdatedAt(snapshot);
        if (!snapshot.exists() || localUpdatedAt > remoteUpdatedAt) {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                upload(document, user, TodoStore.load(context), Math.max(localUpdatedAt, System.currentTimeMillis()));
            }
            return;
        }
        applyRemote(context, snapshot, remoteUpdatedAt);
    }

    private static void applyRemoteIfNewer(Context context, DocumentSnapshot snapshot) {
        long remoteUpdatedAt = remoteUpdatedAt(snapshot);
        if (remoteUpdatedAt <= TodoStore.updatedAt(context)) {
            notifyServerSnapshot(snapshot);
            notifyStatus("synced");
            return;
        }
        applyRemote(context, snapshot, remoteUpdatedAt);
    }

    private static void applyRemote(Context context, DocumentSnapshot snapshot, long updatedAt) {
        List<TodoItem> items = decodeItems(snapshot.get("items"));
        TodoStore.replaceFromCloud(context, items, updatedAt);
        notifyServerItems(items, updatedAt);
        if (listener != null) {
            listener.onTodosUpdated();
        }
        notifyStatus("synced");
    }

    private static void upload(
            DocumentReference document,
            FirebaseUser user,
            List<TodoItem> items,
            long updatedAt
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("items", encodeItems(items));
        data.put("updatedAt", updatedAt);
        data.put("ownerUid", user.getUid());
        data.put("ownerEmail", user.getEmail() == null ? "" : user.getEmail());
        document.set(data)
                .addOnSuccessListener(unused -> {
                    notifyServerItems(items, updatedAt);
                    notifyStatus("synced");
                })
                .addOnFailureListener(error -> notifyStatus("offline"));
    }

    private static DocumentReference documentFor(String uid) {
        return FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("todoLists")
                .document("default");
    }

    private static List<Map<String, Object>> encodeItems(List<TodoItem> items) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TodoItem item : items) {
            Map<String, Object> value = new HashMap<>();
            value.put("id", item.id);
            value.put("text", item.text);
            value.put("done", item.done);
            encoded.add(value);
        }
        return encoded;
    }

    private static List<TodoItem> decodeItems(Object raw) {
        List<TodoItem> items = new ArrayList<>();
        if (!(raw instanceof List)) {
            return items;
        }
        for (Object entry : (List<?>) raw) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> value = (Map<?, ?>) entry;
            Object id = value.get("id");
            Object text = value.get("text");
            Object done = value.get("done");
            if (id instanceof Number && text instanceof String && done instanceof Boolean) {
                items.add(new TodoItem(((Number) id).longValue(), (String) text, (Boolean) done));
            }
        }
        return items;
    }

    private static long remoteUpdatedAt(DocumentSnapshot snapshot) {
        if (snapshot == null) {
            return 0L;
        }
        Object value = snapshot.get("updatedAt");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static int itemCount(DocumentSnapshot snapshot) {
        Object raw = snapshot == null ? null : snapshot.get("items");
        return raw instanceof List ? ((List<?>) raw).size() : 0;
    }

    private static synchronized void stopRegistration() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
    }

    private static void notifyStatus(String status) {
        if (listener != null && !status.equals(lastStatus)) {
            lastStatus = status;
            listener.onStatusChanged(status);
        }
    }

    private static void notifyServerState(int itemCount, long updatedAt) {
        if (listener != null) {
            listener.onServerStateChanged(itemCount, updatedAt);
        }
    }

    private static void notifyServerSnapshot(DocumentSnapshot snapshot) {
        List<TodoItem> items = decodeItems(snapshot == null ? null : snapshot.get("items"));
        notifyServerItems(items, remoteUpdatedAt(snapshot));
    }

    private static void notifyServerItems(List<TodoItem> items, long updatedAt) {
        notifyServerState(items.size(), updatedAt);
        if (listener != null) {
            listener.onServerItemsChanged(new ArrayList<>(items), updatedAt);
        }
    }
}
