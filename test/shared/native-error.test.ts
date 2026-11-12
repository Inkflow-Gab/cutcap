import { describe, expect, it } from "vitest";
import { nativeMessage } from "../../src/renderer/platform/native-error";

/**
 * Capacitor rejects a native call with the error string itself, not with an Error. That is the
 * whole reason this helper exists: reading `.message` off a rejected native call gives undefined,
 * which turned every permission refusal into a blank panel.
 */
describe("nativeMessage", () => {
  it("uses a rejected string, which is what a native plugin sends", () => {
    expect(nativeMessage("Allow access to photos in Settings to browse your photos.", "fallback")).toBe(
      "Allow access to photos in Settings to browse your photos."
    );
  });

  it("uses a real Error's message", () => {
    expect(nativeMessage(new Error("the disk is full"), "fallback")).toBe("the disk is full");
  });

  it("ignores an Error with an empty message rather than showing nothing", () => {
    expect(nativeMessage(new Error(""), "fallback")).toBe("fallback");
    expect(nativeMessage(new Error("   "), "fallback")).toBe("fallback");
  });

  it("ignores an empty or blank string", () => {
    expect(nativeMessage("", "fallback")).toBe("fallback");
    expect(nativeMessage("  \n ", "fallback")).toBe("fallback");
  });

  it("falls back for the shapes that carry no text at all", () => {
    expect(nativeMessage(undefined, "fallback")).toBe("fallback");
    expect(nativeMessage(null, "fallback")).toBe("fallback");
    expect(nativeMessage(42, "fallback")).toBe("fallback");
    expect(nativeMessage({}, "fallback")).toBe("fallback");
    expect(nativeMessage({ code: "denied" }, "fallback")).toBe("fallback");
  });

  it("reads a message nested under error, the shape some bridges use", () => {
    expect(nativeMessage({ error: "not allowed" }, "fallback")).toBe("not allowed");
  });
});
