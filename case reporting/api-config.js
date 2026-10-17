if (window.location.hostname === "localhost") {
  window.HARBOR_API_URL = "http://localhost:8080";
} else if (window.location.hostname === "127.0.0.1") {
  window.HARBOR_API_URL = "http://127.0.0.1:8080";
} else {
  window.HARBOR_API_URL = "";
}
