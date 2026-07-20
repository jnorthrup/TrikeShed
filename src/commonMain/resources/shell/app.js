window.trikeshed = window.trikeshed || {};
window.trikeshed.shellVersion = "1.0.0";
window.trikeshed.bootstrap = function() {
    var root = document.getElementById("forge-root");
    if (root) root.classList.add("trikeshed-fade-in");
};
if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", window.trikeshed.bootstrap);
} else {
    window.trikeshed.bootstrap();
}
