// Runs before the page is painted, so a dark-mode user never sees a white flash.
(function () {
  try {
    var mode = localStorage.getItem("soapcli.theme") || "auto";
    var dark = mode === "dark" || (mode === "auto" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    if (dark) document.documentElement.classList.add("dark");
  } catch (e) {
    /* storage may be blocked: stay with the light theme */
  }
})();
