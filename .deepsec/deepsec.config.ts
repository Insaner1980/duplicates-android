import { defineConfig } from "deepsec/config";
import { kotlinSecurityPlugin } from "./matchers/kotlin-security.mjs";

export default defineConfig({
  plugins: [kotlinSecurityPlugin],
  projects: [
    {
      id: "duplicates-android",
      root: ".."
    }
  ]
});
