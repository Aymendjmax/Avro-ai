export async function postJson(path, body) {
  return fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "same-origin",
  });
}

export async function sessionSignedIn() {
  const response = await fetch("/api/session", { credentials: "same-origin" });
  const body = await response.json();
  return body.signedIn === true;
}
