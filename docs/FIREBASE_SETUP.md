# Firebase setup

The app keeps todos locally first and enables Firestore sync after Google sign-in.

1. Create or open a Firebase project.
2. Register an Android app with package name `com.wns544.nudgescreen`.
3. Add the debug and release SHA-1/SHA-256 certificate fingerprints to the Android app.
4. Download `google-services.json` and place it at `app/google-services.json`.
5. In Firebase Authentication, enable the Google provider.
6. Create a Cloud Firestore database.
7. Publish the rules in `firestore.rules`.
8. Rebuild and install the app, then open Settings and tap **Sync with Google**.

Todo data is stored at:

```text
users/{firebaseAuthUid}/todoLists/default
```

The whole ordered todo list is written atomically. The newest `updatedAt` value wins when a device first signs in. Firestore's local cache handles temporary network loss, while the existing device-protected local store remains the source used by the lock screen.

Do not use test-mode Firestore rules in production. The included rules only allow an authenticated user to access documents under their own UID.
