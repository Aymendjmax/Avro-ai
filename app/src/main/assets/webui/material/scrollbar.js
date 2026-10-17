/**
 * One floating scrollbar owns paint and pointer input for its scrollable host.
 * Native bars are hidden without a layout gutter; wheel and keyboard remain with the host.
 */
const IDLE_MS = 900;

/**
 * Draws `bar` over the right gutter of the scrollable `host`.
 *
 * Returns the detach function, so a component owns the lifetime of one scroller.
 */
export function attachScrollbar(host, bar) {
  let idleTimer = 0;
  const controller = new AbortController();
  const thumb = bar.firstElementChild;
  // The bar lives beside the scroller, so it copies the box the scroller actually paints: the chat
  // column starts under the top bar and runs under the composer.
  const place = () => {
    const box = host.getBoundingClientRect();
    const parent = bar.offsetParent?.getBoundingClientRect() ?? box;
    bar.style.top = `${Math.round(box.top - parent.top)}px`;
    bar.style.height = `${Math.round(box.height)}px`;
    // Pin the gutter to the scroller's own right edge, whatever box the overlay is positioned in.
    bar.style.left = `${Math.round(box.right - parent.left - bar.offsetWidth)}px`;
    bar.style.right = "auto";
  };
  const paint = () => {
    const scrollable = host.scrollHeight - host.clientHeight;
    bar.classList.toggle("scrollable", scrollable > 1);
    if (scrollable <= 1) return;
    const height = Math.max(28, (host.clientHeight * host.clientHeight) / host.scrollHeight);
    const top = (host.scrollTop / scrollable) * (host.clientHeight - height);
    thumb.style.top = `${Math.round(top)}px`;
    thumb.style.height = `${Math.round(height)}px`;
  };
  const wake = () => {
    paint();
    bar.classList.add("scrolling");
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => bar.classList.remove("scrolling"), IDLE_MS);
  };
  let drag = null;
  const pointerDown = (event) => {
    if (event.button !== 0 || !bar.classList.contains("scrollable") || host.inert) return;
    event.preventDefault();
    event.stopPropagation();
    // The host's existing scroll owner receives the same user-input cancellation as a content drag.
    host.dispatchEvent(new Event("pointerdown"));
    bar.setPointerCapture(event.pointerId);
    const box = thumb.getBoundingClientRect();
    const onThumb = event.clientY >= box.top - 8 && event.clientY <= box.bottom + 8;
    if (!onThumb) {
      const travel = host.clientHeight - thumb.offsetHeight;
      if (travel > 0) host.scrollTop = ((event.clientY - bar.getBoundingClientRect().top - box.height / 2) / travel) *
        (host.scrollHeight - host.clientHeight);
    }
    drag = { id: event.pointerId, y: event.clientY, top: host.scrollTop };
    bar.classList.add("dragging");
    wake();
  };
  const pointerMove = (event) => {
    if (drag?.id !== event.pointerId) return;
    const scrollable = host.scrollHeight - host.clientHeight;
    const travel = host.clientHeight - thumb.offsetHeight;
    if (scrollable <= 0 || travel <= 0) return;
    host.scrollTop = drag.top + ((event.clientY - drag.y) / travel) * scrollable;
  };
  const pointerUp = (event) => {
    if (drag?.id !== event.pointerId) return;
    drag = null;
    bar.classList.remove("dragging");
    if (bar.hasPointerCapture(event.pointerId)) bar.releasePointerCapture(event.pointerId);
  };
  const entered = (event) => { if (event.pointerType !== "touch") bar.classList.add("hover"); };
  const left = () => bar.classList.remove("hover");
  bar.addEventListener("pointerdown", pointerDown);
  bar.addEventListener("pointermove", pointerMove);
  bar.addEventListener("pointerup", pointerUp);
  bar.addEventListener("pointercancel", pointerUp);
  bar.addEventListener("lostpointercapture", pointerUp);
  bar.addEventListener("pointerenter", entered);
  bar.addEventListener("pointerleave", left);
  bar.addEventListener("wheel", (event) => {
    event.preventDefault();
    host.dispatchEvent(new Event("wheel"));
    const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? host.clientHeight : 1;
    host.scrollTop += event.deltaY * unit;
  }, { passive: false, signal: controller.signal });
  host.addEventListener("scroll", wake, { passive: true });
  host.addEventListener("pointerenter", entered);
  host.addEventListener("pointerleave", left);
  const observer = new ResizeObserver(() => { place(); paint(); });
  observer.observe(host);
  /* The scroll box alone is not enough. A list that grows inside a box that keeps its own size never
   * resizes, so a bar watching only the box stays sized for the list that was there when it mounted.
   * Watch the content too, and re-arm when the host hands over a different content element. */
  let content = null;
  const watchContent = () => {
    const next = host.firstElementChild;
    if (next === content) return;
    if (content) observer.unobserve(content);
    content = next;
    if (content) observer.observe(content);
    paint();
  };
  const children = new MutationObserver(watchContent);
  children.observe(host, { childList: true });
  place();
  paint();
  watchContent();
  return () => {
    host.removeEventListener("scroll", wake);
    controller.abort();
    host.removeEventListener("pointerenter", entered);
    host.removeEventListener("pointerleave", left);
    bar.removeEventListener("pointerdown", pointerDown);
    bar.removeEventListener("pointermove", pointerMove);
    bar.removeEventListener("pointerup", pointerUp);
    bar.removeEventListener("pointercancel", pointerUp);
    bar.removeEventListener("lostpointercapture", pointerUp);
    bar.removeEventListener("pointerenter", entered);
    bar.removeEventListener("pointerleave", left);
    clearTimeout(idleTimer);
    children.disconnect();
    observer.disconnect();
  };
}
