import { runSpring } from "../html.js";

// Material 3 press feedback for every clickable control.
//
// Compose screens get this for free from the platform's default Material 3 indication; the WebUI
// has no Compose here, so it paints the same thing itself. Nothing is inserted into a control: the
// state layer and the press wave live in the control's own background (see the ripple section of
// material.css), so no control needs a wrapper, a stacking context, a new containing block or
// overflow clipping.
//
// The press wave is the one the phone actually draws. On Android, material3's ripple() goes through
// androidx.compose.material.ripple's AndroidRippleNode and RippleHostView into the framework
// RippleDrawable, which is always STYLE_PATTERNED (Android 12+); Compose's RippleAnimation.kt is
// only the non-Android path. So the numbers come from the framework:
//
//   RippleHostView      radius = getRippleEndRadius(bounded) = half the diagonal + 10dp, hotspot =
//                       the press point, colour alpha = pressedAlpha (0.1).
//   RippleShader        in_maxRadius = radius * 2.3; the wave is softCircle(center, maxRadius *
//                       progress) whose centre moves from the press point to the control's centre.
//   RippleAnimationSession  enter 0 -> 0.5 over 450ms FastOutSlowIn; exit 0.5 -> 1 over 375ms linear,
//                       delayed until the enter has finished.
//   Ripple.kt           a Press adds a wave and a Release or Cancel is what ends it, so a held press
//                       holds the wave; the state layer only carries hover, focus and drag.
//
// material.css turns the single progress value into the shader's radius, centre and alpha. The
// sparkle pattern is not drawn (owner decision, 2026-10-12).
//
// The wave's play state is a data attribute and never a class. Every control here renders its own
// `class` from component state, so a control whose class changes the moment it is selected - a
// conversation row gaining `selected`, for instance - would rewrite the attribute Preact owns and
// erase a wave still running underneath it. A wave can therefore only end two ways, exactly as in
// Compose: a new press on that same control, or that control's own release.
//
// The selector here and the one in material.css's ripple section are the same list: a control
// either has the layer and the wave, or it is not clickable and has neither.
const HOSTS = [
  "button:not(:disabled)",
  "[role=\"button\"]:not([aria-disabled=\"true\"])",
  "[role=\"menuitem\"]:not([aria-disabled=\"true\"])",
  ".conversation-row",
  ".info-item.sheet-item",
  ".info-group-header",
  ".queued-message",
  ".tool-search-result",
  ".interaction-option", ".prompt-option", ".prompt-radio", ".interaction-trust",
].join(", ");
/** A press that is already inside a dead control must not light anything up. */
const DEAD = "[disabled], [aria-disabled=\"true\"]";
/** RippleAnimation.kt's BoundedRippleExtraRadius, in dp (= px here). */
const BOUNDED_EXTRA_RADIUS = 10;
/** RippleShader.setRadius: in_maxRadius = radius * 2.3. */
const SHADER_RADIUS_SCALE = 2.3;
/** The shader's in_maxRadius for a bounded ripple on a box. */
const maxRadius = (box) => (Math.hypot(box.width, box.height) / 2 + BOUNDED_EXTRA_RADIUS) * SHADER_RADIUS_SCALE;
const GEOMETRY = ["--md-wave-max", "--md-wave-ox", "--md-wave-oy", "--md-wave-cx", "--md-wave-cy"];
/** The wave's play state: held, released while growing, then fading out. */
const WAVE = "data-md-wave";
// App Reduce Motion: the state layer stays and changes instantly, the wave does not run at all.
const reduced = () => document.querySelector(".shell")?.dataset.reduceMotion === "true";

/**
 * The controls whose wave is in one state.
 *
 * Read straight off the DOM instead of a module-level set: a wave whose host is unmounted mid-exit
 * never reports its own animation end, so a set here would only ever grow.
 */
const waving = (state) => [...document.querySelectorAll(`[${WAVE}=\"${state}\"]`)];

/** Grows a wave from the press point and holds it there until the press ends. */
function press(host, x, y) {
  if (reduced()) return;
  const box = host.getBoundingClientRect();
  if (!box.width || !box.height) return;
  const style = host.style;
  style.setProperty("--md-wave-max", `${maxRadius(box)}px`);
  style.setProperty("--md-wave-ox", `${x - box.left}px`);
  style.setProperty("--md-wave-oy", `${y - box.top}px`);
  style.setProperty("--md-wave-cx", `${box.width / 2}px`);
  style.setProperty("--md-wave-cy", `${box.height / 2}px`);
  // A press on a control that is still waving is a new wave and restarts from the new press point;
  // every other control keeps the wave it is already running.
  host.removeAttribute(WAVE);
  void host.offsetWidth;
  host.setAttribute(WAVE, "in");
}
/** RippleAnimationSession.computeDelay: the exit starts only once the 450ms enter has finished. */
function release() {
  for (const host of waving("in")) {
    const entering = host.getAnimations().some(animation => animation.animationName === "md-wave-enter" && animation.playState === "running");
    host.setAttribute(WAVE, entering ? "released" : "out");
  }
}
function settled(host) {
  host.removeAttribute(WAVE);
  for (const property of GEOMETRY) host.style.removeProperty(property);
}
/** One delegated listener per interaction keeps every list, menu and dialog covered without wiring. */
export function installTouchFeedback() {
  const switches = new WeakMap();
  function drawSwitch(node, value) {
    node.style.setProperty("--switch-size", value.size);
    node.style.setProperty("--switch-offset", value.offset);
  }
  function animateSwitch(node) {
    const checked = node.dataset.checked === "true", pressed = node.hasAttribute("data-switch-pressed");
    const size = pressed ? 28 : checked ? 24 : 16;
    const offset = pressed ? checked ? 22 : 2 : checked ? 24 : 8;
    let value = switches.get(node);
    if (!value) {
      value = { size, offset, cancel: null };
      switches.set(node, value);
      drawSwitch(node, value);
      return;
    }
    value.cancel?.();
    if (!node.isConnected || pressed || reduced()) {
      value.size = size; value.offset = offset;
      value.sizeVelocity = 0; value.offsetVelocity = 0;
      drawSwitch(node, value);
      return;
    }
    value.cancel = runSpring(value, {
      size: { target: size, stiffness: 1400, damping: .9 },
      offset: { target: offset, stiffness: 1400, damping: .9 },
    }, current => drawSwitch(node, current));
  }
  const switchChanges = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === "attributes" && record.target.matches(".tool-switch")) animateSwitch(record.target);
      else if (record.type === "attributes") document.querySelectorAll(".tool-switch").forEach(animateSwitch);
      for (const node of record.addedNodes ?? []) {
        if (node.nodeType !== 1) continue;
        if (node.matches(".tool-switch")) animateSwitch(node);
        node.querySelectorAll(".tool-switch").forEach(animateSwitch);
      }
      for (const node of record.removedNodes ?? []) {
        if (node.nodeType !== 1) continue;
        const removed = node.matches(".tool-switch") ? [node] : [...node.querySelectorAll(".tool-switch")];
        removed.forEach(item => switches.get(item)?.cancel?.());
      }
    }
  });
  switchChanges.observe(document.body, { subtree: true, childList: true, attributes: true,
    attributeFilter: ["data-checked", "data-switch-pressed", "data-reduce-motion"] });
  document.querySelectorAll(".tool-switch").forEach(animateSwitch);
  function pressSwitch(host) {
    const node = host.matches(".tool-switch") ? host : host.querySelector(".tool-switch");
    if (!node) return;
    animateSwitch(node);
    node.setAttribute("data-switch-pressed", "true");
    animateSwitch(node);
  }
  function releaseSwitches() {
    document.querySelectorAll("[data-switch-pressed]").forEach(node => {
      node.removeAttribute("data-switch-pressed");
      animateSwitch(node);
    });
  }
  const geometry = new ResizeObserver(entries => {
    for (const { target: host } of entries) {
      if (!host.isConnected || !host.hasAttribute(WAVE)) { geometry.unobserve(host); continue; }
      const box = host.getBoundingClientRect();
      host.style.setProperty("--md-wave-max", `${maxRadius(box)}px`);
      host.style.setProperty("--md-wave-cx", `${box.width / 2}px`);
      host.style.setProperty("--md-wave-cy", `${box.height / 2}px`);
    }
  });
  addEventListener("pointerdown", (event) => {
    const host = event.target.closest?.(HOSTS);
    if (!host || host.matches(DEAD) || host.querySelector("input:disabled") || event.target.closest(DEAD)) return;
    pressSwitch(host);
    press(host, event.clientX, event.clientY);
    if (host.hasAttribute(WAVE)) geometry.observe(host);
  }, { passive: true, capture: true });
  // A Release or a Cancel is what ends a wave. A touch that turns into a scroll is cancelled by the
  // browser, so a fling over a list fades its wave out exactly like Compose drops the press.
  addEventListener("pointerup", releaseSwitches, { passive: true, capture: true });
  addEventListener("pointercancel", releaseSwitches, { passive: true, capture: true });
  addEventListener("pointerup", release, { passive: true, capture: true });
  addEventListener("pointercancel", release, { passive: true, capture: true });
  // Keyboard activation is a press too, so it grows from the middle and holds until the key is up.
  addEventListener("keydown", (event) => {
    if (event.repeat || (event.key !== " " && event.key !== "Enter")) return;
    const host = event.target.closest?.(HOSTS);
    if (!host || host.matches(DEAD) || host.querySelector("input:disabled") || event.target.closest(DEAD)) return;
    pressSwitch(host);
    const box = host.getBoundingClientRect();
    press(host, box.left + box.width / 2, box.top + box.height / 2);
    if (host.hasAttribute(WAVE)) geometry.observe(host);
  }, { passive: true, capture: true });
  addEventListener("keyup", releaseSwitches, { passive: true, capture: true });
  addEventListener("keyup", release, { passive: true, capture: true });
  addEventListener("blur", (event) => {
    if (event.target === window) { releaseSwitches(); release(); }
  }, { passive: true, capture: true });
  addEventListener("animationend", (event) => {
    if (event.animationName === "md-wave-enter" && event.target.getAttribute(WAVE) === "released") {
      event.target.setAttribute(WAVE, "out");
    }
    if (event.animationName === "md-wave-exit") { geometry.unobserve(event.target); settled(event.target); }
  }, { passive: true, capture: true });
}
