const firebaseConfig = {
  apiKey: "AIzaSyBGrMX0sPTaYCU6PAOGEhw4qyGlLPFGQx0",
  authDomain: "nudgescreen-todo.firebaseapp.com",
  projectId: "nudgescreen-todo",
  storageBucket: "nudgescreen-todo.firebasestorage.app",
  messagingSenderId: "899868622322"
};

firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();

const qs = new URLSearchParams(location.search);
const widgetMode = qs.get("widget") === "1";
const statusEl = document.querySelector("#status");
const signedOutEl = document.querySelector("#signedOut");
const signedInEl = document.querySelector("#signedIn");
const todosEl = document.querySelector("#todos");
const newTodoEl = document.querySelector("#newTodo");
let unsubscribe = null;
let currentItems = [];
let currentDoc = null;

if (widgetMode) {
  document.body.classList.add("widget");
}

document.querySelector("#close").addEventListener("click", () => window.nudge.close());
document.querySelector("#minimize").addEventListener("click", () => window.nudge.minimize());
document.querySelector("#signIn").addEventListener("click", async () => {
  const provider = new firebase.auth.GoogleAuthProvider();
  await auth.signInWithPopup(provider);
});
document.querySelector("#signOut").addEventListener("click", () => auth.signOut());
document.querySelector("#copyAll").addEventListener("click", () => {
  window.nudge.copy(currentItems.map((item) => item.text).join("\n"));
});
document.querySelector("#addTodo").addEventListener("click", addTodo);
newTodoEl.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
    addTodo();
  }
});

auth.onAuthStateChanged((user) => {
  if (unsubscribe) {
    unsubscribe();
    unsubscribe = null;
  }
  currentItems = [];
  currentDoc = null;
  if (!user) {
    statusEl.textContent = "로그인이 필요합니다.";
    signedOutEl.classList.remove("hidden");
    signedInEl.classList.add("hidden");
    renderTodos();
    return;
  }
  statusEl.textContent = `${user.email || "Google 계정"} · 동기화 중`;
  signedOutEl.classList.add("hidden");
  signedInEl.classList.remove("hidden");
  currentDoc = db.collection("users").doc(user.uid).collection("todoLists").doc("default");
  unsubscribe = currentDoc.onSnapshot((snapshot) => {
    const data = snapshot.exists ? snapshot.data() : {};
    currentItems = Array.isArray(data.items) ? data.items : [];
    const updatedAt = data.updatedAt ? new Date(data.updatedAt) : null;
    statusEl.textContent = `${user.email || "Google 계정"} · 메모 ${currentItems.length}개`
      + (updatedAt ? ` · ${updatedAt.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : "");
    renderTodos();
  });
});

async function addTodo() {
  const text = newTodoEl.value.trim();
  if (!text || !currentDoc) {
    return;
  }
  const nextItems = [
    { id: Date.now(), text, done: false },
    ...currentItems
  ];
  await currentDoc.set({
    items: nextItems,
    updatedAt: Date.now(),
    ownerUid: auth.currentUser.uid,
    ownerEmail: auth.currentUser.email || ""
  });
  newTodoEl.value = "";
}

function renderTodos() {
  todosEl.innerHTML = "";
  if (!currentItems.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "아직 메모가 없습니다.";
    todosEl.appendChild(empty);
    return;
  }
  currentItems.forEach((item) => {
    const row = document.createElement("article");
    row.className = "todo";
    const text = document.createElement("p");
    text.textContent = item.text || "";
    const copy = document.createElement("button");
    copy.textContent = "복사";
    copy.addEventListener("click", () => window.nudge.copy(item.text || ""));
    row.append(text, copy);
    todosEl.appendChild(row);
  });
}
