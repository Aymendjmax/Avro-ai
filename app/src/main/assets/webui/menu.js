// AvroDropdownMenu: the one popup menu the whole browser frame opens its items from.
import { useLayoutEffect, useRef } from "./vendor/preact-hooks.mjs";
import { html, runSpring } from "./html.js";
import { t } from "./i18n.js";
import {
  icon, ICON_CALL_SPLIT, ICON_LOGOUT, ICON_PSYCHOLOGY, ICON_SEARCH, ICON_SHARE,
} from "./icons.js";

/**
 * 24 dp corners, 48 dp items with an inset capsule highlight, a spring that carries the scale and
 * the alpha, and a focus trap that returns ownership to whoever opened it. Every menu in the frame
 * (top bar, drawer row, message row) is a MoreMenu so they cannot drift apart.
 */
export function MoreMenu({ expanded, reduceMotion, anchor, onSignOut, onClose, onExited, children, above = false, matchAnchorWidth = false, onFork, onShare, pageActionsEnabled, onSystemPrompt, promptEnabled, onSearch }) {
  const menu = useRef(null);
  const layer = useRef(null);
  const motion = useRef({ scale: 0.8, alpha: 0, scaleVelocity: 0, alphaVelocity: 0 });
  useLayoutEffect(() => {
    const popup = layer.current;
    popup.showModal();
    const node = menu.current;
    const measure = () => {
      const bounds = anchor.current.getBoundingClientRect();
      // ExposedDropdownMenu: the menu takes the anchor field's width and its start edge.
      if (matchAnchorWidth) { node.style.width = `${bounds.width}px`; node.style.maxWidth = "none"; }
      const margin = 48;
      const available = above ? bounds.top - 8 - 4 : matchAnchorWidth
        ? bounds.top > innerHeight || bounds.bottom < 0 ? innerHeight - margin * 2
          : Math.max(bounds.top - margin, innerHeight - margin - bounds.bottom)
        : innerHeight - margin * 2;
      node.style.maxHeight = `${Math.max(0, available)}px`;
      // DropdownMenuPositionProvider (LTR, zero offset): start at the anchor's left edge, else end
      // at its right edge, else hug the window's right edge; below the anchor unless it would run
      // past the window bottom, then above it.
      const width = node.offsetWidth;
      const height = node.offsetHeight;
      const left = above ? Math.max(8, Math.min(innerWidth - width - 8, bounds.left))
        : [bounds.left, bounds.right - width, innerWidth - width].find(x => x >= 0 && x + width <= innerWidth) ?? 0;
      const top = above ? Math.max(8, bounds.top - height - 4)
        : [bounds.bottom, bounds.top - height, innerHeight - height - 48]
          .find(y => y >= 48 && y + height <= innerHeight - 48) ?? Math.max(0, (innerHeight - height) / 2);
      node.style.left = `${left}px`;
      node.style.top = `${top}px`;
      const pivotX = bounds.left >= left + node.offsetWidth ? 1 : bounds.right <= left ? 0
        : ((Math.max(bounds.left, left) + Math.min(bounds.right, left + node.offsetWidth)) / 2 - left) / node.offsetWidth;
      const pivotY = top >= bounds.bottom ? 0 : top + node.offsetHeight <= bounds.top ? 1
        : ((Math.max(bounds.top, top) + Math.min(bounds.bottom, top + node.offsetHeight)) / 2 - top) / node.offsetHeight;
      node.style.transformOrigin = `${pivotX * 100}% ${pivotY * 100}%`;
    };
    const geometry = new ResizeObserver(measure);
    [node, anchor.current].forEach((element) => geometry.observe(element, { box: "border-box" }));
    window.addEventListener("resize", measure);
    measure();
    node.querySelector("[role^=menuitem]:not([disabled])")?.focus();
    return () => { popup.close(); geometry.disconnect(); window.removeEventListener("resize", measure); };
  }, []);
  useLayoutEffect(() => {
    const node = menu.current;
    const values = motion.current;
    if (reduceMotion) { values.scale = expanded ? 1 : 0.8; values.scaleVelocity = 0; }
    return runSpring(values, {
      scale: { target: expanded ? 1 : 0.8, stiffness: 1400, damping: Math.fround(0.9) },
      alpha: { target: expanded ? 1 : 0, stiffness: 3800, damping: 1 },
    }, value => {
      node.style.transform = `scale(${value.scale})`;
      node.style.opacity = String(value.alpha);
    }, () => {
      if (!expanded) {
        const ownedFocus = layer.current.contains(document.activeElement);
        layer.current.close();
        onExited(ownedFocus);
      }
    });
  }, [expanded, reduceMotion]);
  useLayoutEffect(() => {
    const onKey = (event) => {
      if (!layer.current.contains(document.activeElement)) return;
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onClose(); }
      if (["Tab", "ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) {
        event.preventDefault();
        const controls = [...menu.current.querySelectorAll("[role^=menuitem]:not([disabled])")];
        const current = controls.indexOf(document.activeElement);
        const next = event.key === "Home" ? 0 : event.key === "End" ? controls.length - 1
          : (current + (event.shiftKey || event.key === "ArrowUp" ? -1 : 1) + controls.length) % controls.length;
        controls[next]?.focus();
        controls[next]?.scrollIntoView({ block: "nearest" });
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return html`
    <dialog class="menu-popup-layer" ref=${layer} aria-label=${t.options}
      onCancel=${(event) => { event.preventDefault(); onClose(); }} onPointerDown=${(event) => {
      if (!menu.current.contains(event.target)) { event.preventDefault(); onClose(); }
      event.stopPropagation();
    }} onWheel=${(event) => { if (!menu.current.contains(event.target)) event.preventDefault(); }}
      onContextMenu=${(event) => event.preventDefault()}>
    <div class=${`dropdown ${above ? "composer-menu" : ""}`} role="menu" ref=${menu}>
      ${children || html`
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!onSearch || !pageActionsEnabled} onClick=${onSearch}>
        ${icon(ICON_SEARCH)}<span>${t.conversationSearch}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!promptEnabled} onClick=${onSystemPrompt}>
        ${icon(ICON_PSYCHOLOGY)}<span>${t.systemPrompt}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!pageActionsEnabled} onClick=${onFork}>
        ${icon(ICON_CALL_SPLIT)}<span>${t.forkConversation}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!pageActionsEnabled} onClick=${onShare}>
        ${icon(ICON_SHARE)}<span>${t.share}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" onClick=${onSignOut}>
        ${icon(ICON_LOGOUT)}<span>${t.signOut}</span>
      </button>`}
    </div>
    </dialog>`;
}
