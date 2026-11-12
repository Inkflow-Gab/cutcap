import { createRoot } from "react-dom/client";
import { loadFonts } from "../engine/fonts";
import { App } from "./ui/App";
import { installAndroidApi } from "./platform/android";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/look.css";
import "./styles/ai.css";
import "./styles/mobile.css";

// On Android the renderer runs inside a WebView with no Node and no Electron, so the layer
// under window.openroll is installed before anything reads it. The desktop preload keeps it
// as it is.
installAndroidApi();

document.documentElement.dataset.platform = window.openroll.platform;

// Text boxes measure with these faces, so the first render waits for them.
void loadFonts(document.fonts).then(() => createRoot(document.getElementById("root")!).render(<App />));
