import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, useDialog } from "./html.js";
import { CircularProgress } from "./material/progress.js";
import { attachScrollbar } from "./material/scrollbar.js";
import { t } from "./i18n.js";
import {
  icon, ICON_ADD,
  ICON_MENU, ICON_MORE_VERT, ICON_SEARCH, ICON_PIN, ICON_CLOSE,
  ICON_DELETE, ICON_EDIT, ICON_REFRESH,
  ICON_ARROW_BACK, ICON_CHEVRON_DOWN,
} from "./icons.js";
import { MoreMenu } from "./menu.js";
import { postJson } from "./api.js";
import { sync, useSync } from "./sync.js";
import { MessageList } from "./messages.js";
import { Composer } from "./composer.js";
/*
 * Chat frame, mirroring the app's chat screen (ChatTopBar, ChatDrawerContent, ChatBottomBar).
 * Controls the browser cannot use yet are shown as in the app but disabled.
 */
// ChatDrawerHost.usesSideBySideDrawer: wider than DRAWER_MAX_WIDTH (360) + CHAT_APP_WIDTH_THRESHOLD (600).
const SIDE_BY_SIDE_QUERY = "(min-width: 960.02px)";
// DrawerPinned is the persisted wide-window preference; the narrow window never reads or writes it.
const DRAWER_PINNED_KEY = "agora.drawerPinned";
const readDrawerPinned = () => { try { return localStorage.getItem(DRAWER_PINNED_KEY) === "true"; } catch (_) { return false; } };

function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);
  return matches;
}

function DrawerButton({ className, iconPath, label, onClick, disabled = true }) {
  return html`
    <button class=${`drawer-button ${className}`} type="button" disabled=${disabled} onClick=${onClick}>
      ${icon(iconPath)}<span>${label}</span>
    </button>`;
}

/**
 * A drawer row: 44 dp with 2 dp above and below, a capsule highlight on secondaryContainer when
 * selected, the title in bodyLarge, and an 18 dp slot for the generating spinner or unread dot.
 */
function ConversationRow({ conversation, selected, onSelect, onMenu }) {
  // resolveDrawerConversationIndicator: generating first; unread only when not selected.
  const indicator = conversation.generating ? "generating"
    : conversation.unread && !selected ? "unread" : null;
  return html`
    <div class="conversation-slot" role="listitem" data-conversation-id=${conversation.id}>
    <button class=${selected ? "conversation-row selected" : "conversation-row"} type="button"
      aria-current=${selected ? "true" : null} onClick=${() => onSelect(conversation.id)}
      onContextMenu=${event => { event.preventDefault(); onMenu(conversation, event.currentTarget); }}>
      <span class="conversation-title">${conversation.title}</span>
      <span class="conversation-indicator">
        ${indicator === "generating" && html`<${CircularProgress} size=${18} stroke=${2} />`}
        ${indicator === "unread" && html`<span class="unread-dot" role="img" aria-label=${t.unreadGeneration}></span>`}
      </span>
    </button>
    <button class="conversation-more" type="button" title=${t.options} aria-label=${t.options}
      onClick=${event => onMenu(conversation, event.currentTarget)}>${icon(ICON_MORE_VERT)}</button>
    </div>`;
}

/** ChatDrawerContent: title, search, New Chat, then the conversation list. */
function DrawerContent({ conversations, openId, onSelect, connected, loading, hasMore, reduceMotion,
  searchQuery, searchResults, searchLoading, searchFailed, onRequestRename, onRequestDelete, switching }) {
  const searching = !!searchQuery.trim();
  const list = useRef(null), bar = useRef(null), focusIndex = useRef(null);
  const [viewport, setViewport] = useState({ top: 0, height: 500 });
  const [menu, setMenu] = useState(null), [menuOpen, setMenuOpen] = useState(false);
  // Chrome runs no CSS transition on its own scrollbar, so this list draws its bar beside the native
  // gutter through the same owner the chat uses. The gutter itself stays and keeps every hit target.
  useEffect(() => attachScrollbar(list.current, bar.current), []);
  const rows = useMemo(() => {
    const pinned = conversations.filter(item => item.isPinned);
    const ordinary = conversations.filter(item => !item.isPinned);
    const result = [];
    let top = 0;
    const heading = (key, title) => { result.push({ key, title, top, height: 36 }); top += 36; };
    const append = item => { result.push({ key: item.id, conversation: item, top, height: 44 }); top += 44; };
    if (pinned.length) { heading("pinned-heading", t.pinned); pinned.forEach(append); }
    if (pinned.length && ordinary.length) heading("conversations-heading", t.conversations);
    ordinary.forEach(append);
    return { items: result, height: top };
  }, [conversations]);
  const visible = rows.items.filter(item => item.top + item.height >= viewport.top - 264 &&
    item.top <= viewport.top + viewport.height + 264);
  useLayoutEffect(() => {
    const node = list.current;
    const resize = () => setViewport({ top: node.scrollTop, height: node.clientHeight });
    const observer = new ResizeObserver(resize);
    observer.observe(node);
    resize();
    return () => observer.disconnect();
  }, []);
  useLayoutEffect(() => {
    if (!searching && connected && hasMore && !loading && viewport.top + viewport.height >= rows.height - 88) sync.loadMore();
    if (focusIndex.current != null) {
      const target = rows.items[focusIndex.current]?.conversation;
      if (target) list.current.querySelector(`[data-conversation-id="${CSS.escape(target.id)}"] .conversation-row`)?.focus({ preventScroll: true });
      focusIndex.current = null;
    }
    if (menu && !menu.anchor.isConnected) setMenuOpen(false);
  }, [viewport, rows, hasMore, loading, connected, searching]);
  useLayoutEffect(() => { list.current.scrollTop = 0; }, [searching]);
  function highlight(text) {
    if (!searchQuery.trim()) return text;
    const parts = [], lower = text.toLowerCase(), query = searchQuery.toLowerCase();
    let from = 0, index;
    while ((index = lower.indexOf(query, from)) >= 0) {
      parts.push(text.slice(from, index), html`<mark>${text.slice(index, index + query.length)}</mark>`);
      from = index + query.length;
    }
    parts.push(text.slice(from));
    return parts;
  }
  function keyboard(event) {
    if (searching) return;
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    const current = event.target.closest("[data-conversation-id]")?.dataset.conversationId;
    let index = rows.items.findIndex(item => item.key === current);
    const direction = event.key === "ArrowUp" || event.key === "End" ? -1 : 1;
    index = event.key === "Home" ? 0 : event.key === "End" ? rows.items.length - 1 : index + direction;
    while (index >= 0 && index < rows.items.length && !rows.items[index].conversation) index += direction;
    if (!rows.items[index]) return;
    event.preventDefault();
    focusIndex.current = index;
    list.current.scrollTop = Math.max(0, rows.items[index].top - viewport.height / 2 + 22);
    setViewport({ top: list.current.scrollTop, height: list.current.clientHeight });
  }
  return html`
    <h2 class="drawer-title">${t.conversations}</h2>
    <div class="drawer-search">
      ${icon(ICON_SEARCH)}
      <input type="text" enterkeyhint="search" placeholder=${t.searchHint} aria-label=${t.searchHint} disabled=${!connected}
        value=${searchQuery} onInput=${event => sync.search(event.currentTarget.value)} />
      <span class="drawer-search-trailing">
        <span class="drawer-search-progress" data-active=${searchLoading} aria-hidden=${!searchLoading}>
          <${CircularProgress} size=${18} stroke=${2} label=${t.loading} />
        </span>
        <button class="drawer-search-clear" type="button" aria-label=${t.clearSearch}
          data-active=${!!searchQuery && !searchLoading} disabled=${!searchQuery || searchLoading}
          onClick=${() => sync.search("")}>${icon(ICON_CLOSE)}</button>
      </span>
    </div>
    ${!searching && html`
    <${DrawerButton} className="filled new-chat" iconPath=${ICON_ADD} label=${t.newChat}
      disabled=${!connected} onClick=${() => onSelect(null)} />
    `}
    <div class="drawer-list" ref=${list} role="list" aria-label=${t.conversations} aria-busy=${searching ? searchLoading : loading}
      onKeyDown=${keyboard} onScroll=${event => setViewport({ top: event.currentTarget.scrollTop, height: event.currentTarget.clientHeight })}>
      ${searching ? html`
        ${searchResults.map(result => html`<div key=${result.id} role="listitem"><button class="drawer-search-result" type="button"
          disabled=${!connected} onClick=${() => { sync.search(""); onSelect(result.id); }}>
          <span class="drawer-search-result-heading"><span>${highlight(result.title)}</span>
            ${result.score > 0 && html`<span class="drawer-search-score">${Math.trunc(result.score * 100)}%</span>`}</span>
          ${result.snippets.map(snippet => html`<span class="drawer-search-snippet">${snippet.role === "USER" ? t.searchRoleUser : t.searchRoleModel}: ${highlight(snippet.text)}</span>`)}
        </button></div>`)}
        ${!searchLoading && !searchResults.length && html`<p class="drawer-search-empty" role="status">${searchFailed ? t.searchFailed : t.searchNoResults}</p>`}
      ` : html`<div class="drawer-list-window" style=${{ height: `${rows.height}px` }}>
        ${visible.map(item => html`<div key=${item.key} class="drawer-list-position" style=${{ top: `${item.top}px`, height: `${item.height}px` }}>
          ${item.conversation ? html`<${ConversationRow} conversation=${item.conversation}
            selected=${item.key === openId} onSelect=${onSelect}
            onMenu=${(conversation, anchor) => { setMenu({ conversation, anchor }); setMenuOpen(true); }} />`
            : html`<h3 class="drawer-section">${item.title}</h3>`}
        </div>`)}
      </div>
      ${loading && html`<div class="drawer-list-progress"><${CircularProgress} size=${32} stroke=${3} label=${t.loading} /></div>`}
      `}
    </div>
    <div class="scrollbar-overlay drawer-scrollbar" ref=${bar} aria-hidden="true"><span></span></div>
    ${menu && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion} anchor=${{ current: menu.anchor }}
      onClose=${() => setMenuOpen(false)} onExited=${() => setMenu(null)}>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!connected || switching || menu.conversation.generating}
        onClick=${() => { sync.pin(menu.conversation.id, !menu.conversation.isPinned); setMenuOpen(false); }}>
        ${icon(ICON_PIN)}<span>${menu.conversation.isPinned ? t.unpin : t.pin}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button"
        disabled=${!connected || switching || menu.conversation.generating}
        onClick=${() => { sync.generateTitle(menu.conversation.id); setMenuOpen(false); }}>
        ${icon(ICON_REFRESH)}<span>${t.generateTitle}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!connected || switching || menu.conversation.generating}
        onClick=${() => { onRequestRename({ conversationId: menu.conversation.id, title: menu.conversation.title, anchor: menu.anchor }); setMenuOpen(false); }}>
        ${icon(ICON_EDIT)}<span>${t.rename}</span>
      </button>
      <button class="dropdown-item destructive" role="menuitem" type="button" disabled=${!connected || switching || menu.conversation.generating}
        onClick=${() => { onRequestDelete({ conversationId: menu.conversation.id, title: menu.conversation.title, anchor: menu.anchor }); setMenuOpen(false); }}>
        ${icon(ICON_DELETE)}<span>${t.delete}</span>
      </button>
    </${MoreMenu}>`}`;
}

/**
 * ChatTopBar: title capsule (menu + brand, or the conversation title at conversationTitleSolo)
 * and actions capsule (new chat + more).
 */
function TopBar({ title, subtitle, conversationId, drawerOpen, onToggleDrawer, menuButton, reduceMotion, onSignedOut, connected, onFork, onShare, pageBusy, onSystemPrompt, promptEnabled, search, generating }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [retainedMenu, setRetainedMenu] = useState(false);
  const moreButton = useRef(null);
  const searchField = useRef(null);
  useLayoutEffect(() => { if (search) searchField.current?.focus(); }, [!!search]);
  const titleIdentity = title ? JSON.stringify([conversationId, title]) : "brand";
  const titled = titleFrames => {
    if (!titleFrames.some(frame => frame.identity === titleIdentity)) {
      return [...titleFrames, { identity: titleIdentity, title, subtitle, initialAlpha: 0 }];
    }
    // The token figure arrives on its own, so the frame of the title that is showing takes it
    // without restarting the crossfade; every other frame keeps what it had while it fades out.
    return titleFrames.every(frame => frame.subtitle === subtitle) ? titleFrames
      : titleFrames.map(frame => frame.identity === titleIdentity ? { ...frame, subtitle } : frame);
  };
  const [titleFrames, setTitleFrames] = useState([{ identity: titleIdentity, title, subtitle, initialAlpha: 1 }]);
  const presentations = titled(titleFrames);
  const titleCanvas = useRef(null);
  const titleClip = useRef({ identity: null, target: 0, deadline: 0, animation: null });
  const titleFade = useRef({ identity: titleIdentity, animations: new Map() });
  useLayoutEffect(() => {
    const canvas = titleCanvas.current;
    const clip = titleClip.current;
    // The search bar replaces the whole title capsule, so the canvas is absent while
    // search owns the bar; geometry resumes when the capsule remounts.
    if (!canvas) return undefined;
    const measure = () => {
      const label = [...canvas.querySelectorAll("h1")].find((node) => node.dataset.titleIdentity === titleIdentity);
      if (!label) return;
      const target = Math.min(canvas.clientWidth, 74 + Math.min(label.scrollWidth, 180));
      const initial = clip.identity === null;
      const changed = clip.identity !== titleIdentity;
      if (!changed && clip.target === target && (!reduceMotion || !clip.animation)) return;
      const now = performance.now();
      const from = initial ? target : parseFloat(getComputedStyle(canvas).getPropertyValue("--title-clip-width"));
      canvas.style.setProperty("--title-clip-width", `${from}px`);
      clip.animation?.cancel();
      clip.animation = null;
      if (changed) clip.deadline = now + 400;
      clip.identity = titleIdentity;
      clip.target = target;
      if (initial || reduceMotion || now >= clip.deadline) {
        canvas.style.setProperty("--title-clip-width", `${target}px`);
        clip.deadline = 0;
        return;
      }
      // Identity changes restart; geometry alone rebases within the same original deadline.
      const animation = canvas.animate([
        { "--title-clip-width": `${from}px` }, { "--title-clip-width": `${target}px` },
      ], { duration: clip.deadline - now, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      clip.animation = animation;
      animation.onfinish = () => {
        if (clip.animation !== animation) return;
        canvas.style.setProperty("--title-clip-width", `${clip.target}px`);
        animation.cancel();
        clip.animation = null;
        clip.deadline = 0;
      };
    };
    measure();
    const geometry = new ResizeObserver(measure);
    [canvas, ...canvas.querySelectorAll("h1")].forEach((node) => geometry.observe(node, { box: "border-box" }));
    document.fonts.addEventListener("loadingdone", measure);
    return () => { geometry.disconnect(); document.fonts.removeEventListener("loadingdone", measure); };
  }, [titleIdentity, reduceMotion, titleFrames, !!search]);
  useLayoutEffect(() => {
    const fade = titleFade.current;
    if (fade.identity === titleIdentity) return;
    // The search bar owns the top bar while search is open; the pending identity is not
    // consumed until the capsule remounts and this effect re-runs.
    if (!titleCanvas.current) return;
    const labels = [...titleCanvas.current.querySelectorAll("h1")];
    for (const label of labels) {
      const alpha = getComputedStyle(label).opacity;
      fade.animations.get(label.dataset.titleIdentity)?.cancel();
      label.style.opacity = alpha;
    }
    fade.animations.clear();
    fade.identity = titleIdentity;
    setTitleFrames(presentations);
    for (const label of labels) {
      const identity = label.dataset.titleIdentity;
      const animation = label.animate([{ opacity: getComputedStyle(label).opacity },
        { opacity: identity === titleIdentity ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      fade.animations.set(identity, animation);
      if (identity === titleIdentity) animation.onfinish = () => {
        if (fade.animations.get(identity) !== animation) return;
        labels.forEach((node) => { node.style.opacity = node === label ? "1" : "0"; });
        fade.animations.forEach((owned) => owned.cancel());
        fade.animations.clear();
        setTitleFrames([{ identity, title, subtitle, initialAlpha: 1 }]);
      };
    }
  }, [titleIdentity, !!search]);
  useLayoutEffect(() => () => {
    titleClip.current.animation?.cancel();
    titleFade.current.animations.forEach((animation) => animation.cancel());
  }, []);
  async function signOut() {
    setMenuOpen(false);
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  function closeMenu() {
    setMenuOpen(false);
  }
  return html`
    <header class="top-bar">
      ${search ? html`<div class="capsule conversation-search-bar" aria-busy=${search.searching}>
        <button class="bar-button" type="button" aria-label=${t.close} onClick=${() => sync.dismissConversationSearch()}>${icon(ICON_ARROW_BACK)}</button>
        <input ref=${searchField} type="search" value=${search.query} placeholder=${t.conversationSearch}
          aria-label=${t.conversationSearch} disabled=${!connected} onInput=${event => sync.conversationSearch(event.currentTarget.value)}
          onKeyDown=${event => { if (event.key === "Escape") { event.preventDefault(); sync.dismissConversationSearch(); } }} />
        <span class="conversation-search-count" role="status">${search.failed ? t.searchFailed : `${search.index < 0 ? 0 : search.index + 1}/${search.matches.length}`}</span>
        <button class="bar-button search-previous" type="button" aria-label=${t.searchPrevious} disabled=${search.searching || search.index <= 0}
          onClick=${() => sync.selectSearchMatch(search.index - 1, search.revision)}>${icon(ICON_CHEVRON_DOWN)}</button>
        <button class="bar-button" type="button" aria-label=${t.searchNext} disabled=${search.searching || search.index < 0 || search.index >= search.matches.length - 1}
          onClick=${() => sync.selectSearchMatch(search.index + 1, search.revision)}>${icon(ICON_CHEVRON_DOWN)}</button>
      </div>` : html`
      <div class="capsule title-capsule">
        <div class="title-canvas" ref=${titleCanvas}>
          <div class="title-content">
            <button class="bar-button" type="button" ref=${menuButton} aria-label=${t.menu}
              aria-controls="drawer" aria-expanded=${drawerOpen ? "true" : "false"} onClick=${onToggleDrawer}>
              ${icon(ICON_MENU)}
            </button>
            <div class="title-labels">
              ${presentations.map((frame) => html`<h1 key=${frame.identity}
                class=${frame.title ? (frame.subtitle ? "conversation-bar-title has-subtitle" : "conversation-bar-title") : "brand-title"}
                data-title-identity=${frame.identity} aria-hidden=${frame.identity === titleIdentity ? null : "true"}
                style=${{ opacity: frame.initialAlpha }}><span class="title-text">${frame.title || t.title}</span>${frame.subtitle &&
                  html`<span class="conversation-subtitle">${frame.subtitle}</span>`}</h1>`)}
            </div>
          </div>
        </div>
      </div>
      <div class="capsule actions-capsule">
        <button class="bar-button add" type="button" aria-label=${t.newChat} disabled=${!connected}
          onClick=${() => sync.open(null)}>
          ${icon(ICON_ADD)}
        </button>
        <div class="menu-anchor">
          <button class="bar-button" type="button" ref=${moreButton} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen ? "true" : "false"}
            onClick=${() => { setRetainedMenu(true); setMenuOpen(!menuOpen); }}>
            ${icon(ICON_MORE_VERT)}
          </button>
        </div>
      </div>`}
    </header>
    ${(menuOpen || retainedMenu) && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion}
      anchor=${moreButton} onSignOut=${signOut} onClose=${closeMenu}
      pageActionsEnabled=${connected && !!conversationId && !pageBusy && !generating}
      promptEnabled=${promptEnabled} onSystemPrompt=${() => { closeMenu(); onSystemPrompt(); }}
      onSearch=${() => { closeMenu(); sync.conversationSearch(""); }}
      onFork=${() => { closeMenu(); onFork(); }} onShare=${() => { closeMenu(); onShare(); }}
      onExited=${(ownedFocus) => { setRetainedMenu(false); if (ownedFocus) moreButton.current?.focus(); }} />`}`;
}


/**
 * The drawer overlays the chat with a scrim up to 960 px; wider, it sits beside the chat and the
 * chat narrows, as the app's side-by-side drawer. Both start closed and open from the menu button.
 */
function ConversationDialog({ state, request, onClose: onClosed }) {
  const dialog = useRef(null);
  const returnFocus = useRef(document.querySelector('.actions-capsule [aria-haspopup="menu"]'));
  const onClose = useDialog(dialog, () => {
    if (sent.current) sync.confirmationExited(sent.current.actionId);
    sync.dismissShare(); onClosed();
  }, returnFocus);
  const sent = useRef(false);
  const busy = !!state.pageAction;
  const sharing = request.type === "share";
  const picking = request.type === "system_prompt";
  const prompt = state.composer?.systemPrompt;
  const [selectedPromptId, setSelectedPromptId] = useState(prompt?.selectedId ?? null);
  useEffect(() => { setSelectedPromptId(prompt?.selectedId ?? null); }, [prompt?.selectedId]);
  const activeTitle = prompt?.items.find(item => item.id === prompt.activeId)?.title || t.noSystemPrompt;
  const heading = picking ? t.systemPrompt : sharing ? t.share
    : request.messageId == null ? t.forkConversationTitle : t.forkFromHere;
  const text = state.shareText;
  function close() { if (!busy && !sent.current) onClose(); }
  useEffect(() => {
    if (sent.current && state.pageResult?.actionId === sent.current.actionId) onClose();
  }, [state.pageResult]);
  async function copy() {
    try { await navigator.clipboard.writeText(text); sync.notify(t.copied); }
    catch (_) { sync.notify(t.copyFailed); }
  }
  function download() {
    const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "conversation.md";
    link.click();
    URL.revokeObjectURL(url);
  }
  return html`<dialog ref=${dialog} class=${`attachment-editor page-dialog ${picking ? "prompt-dialog" : ""}`} aria-label=${heading}
    onCancel=${event => { event.preventDefault(); close(); }}
    onClick=${event => { if (event.target === dialog.current) close(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${heading}</h2>
      ${picking ? html`<div class="prompt-options" role="radiogroup" aria-label=${t.systemPrompt}>
        ${[{ id: null, title: t.globalDefault(activeTitle) }, ...(prompt?.items || [])].map(item => html`
          <label class="prompt-option"><span class="prompt-radio"><input type="radio" name="system-prompt" checked=${selectedPromptId === item.id}
            onChange=${() => setSelectedPromptId(item.id)} /></span><span>${item.title}</span></label>`)}
      </div>` : sharing ? (text ? html`<textarea class="share-output" readonly aria-label=${t.share}>${text}</textarea>`
        : html`<${CircularProgress} size=${24} label=${t.loading} />`)
        : html`<p>${t.forkConfirm}</p>`}
      <footer><button type="button" disabled=${busy} onClick=${close}>${sharing ? t.close : t.cancel}</button>
        ${picking ? html`<button type="button" disabled=${state.pendingAction || state.composer?.controls?.lowContextModeEnabled ||
          (selectedPromptId !== null && !prompt?.items.some(item => item.id === selectedPromptId))} onClick=${() => {
            if (sync.editorCommand("system_prompt", { value: selectedPromptId }, request)) onClose();
          }}>${t.save}</button>` : sharing ? text && html`<button type="button" onClick=${copy}>${t.copy}</button><button type="button" onClick=${download}>${t.download}</button>`
          : html`<button type="button" disabled=${busy} onClick=${() => {
            if (sent.current) return;
            sent.current = sync.pageCommand("fork", request,
              request.messageId == null ? {} : { messageId: request.messageId });
            if (!sent.current) onClose();
          }}>${busy ? html`<${CircularProgress} size=${20} stroke=${3} label=${t.loading} />` : t.fork}</button>`}
      </footer>
    </section></dialog>`;
}

/**
 * ChatRenameDialog: the field opens on the stored title and an untouched Save keeps it, so a title
 * the browser never edited is never written back over the stored one.
 */
function ConversationRenameDialog({ conversation, onClose: onClosed }) {
  const dialog = useRef(null);
  const returnFocus = useRef(conversation.anchor);
  const onClose = useDialog(dialog, onClosed, returnFocus);
  const field = useRef(null);
  const edited = useRef(false);
  useLayoutEffect(() => {
    field.current.value = conversation.title;
    field.current.focus();
  }, []);
  function save() {
    if (edited.current) sync.renameConversation(conversation.conversationId, field.current.value);
    onClose();
  }
  return html`<dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.renameChat}
    onCancel=${event => { event.preventDefault(); onClose(); }}
    onClick=${event => { if (event.target === dialog.current) onClose(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${t.renameChat}</h2>
      <input class="rename-field" type="text" ref=${field} aria-label=${t.renameChat}
        onInput=${() => { edited.current = true; }}
        onKeyDown=${event => { if (event.key === "Enter") { event.preventDefault(); save(); } }} />
      <footer><button type="button" onClick=${onClose}>${t.cancel}</button>
        <button type="button" onClick=${save}>${t.save}</button></footer>
    </section></dialog>`;
}

/**
 * ChatDeleteConfirmDialog: CONFIRM, then PENDING while the phone's owner runs its transaction, then
 * FAILED when it refuses. A pending delete cannot be dismissed, so the dialog cannot outlive it.
 */
function ConversationDeleteDialog({ pending, failed, complete, actionId, onConfirm, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, () => { sync.confirmationExited(actionId); onClosed(); });
  useEffect(() => { if (complete) onClose(); }, [complete]);
  function close() { if (!pending) onClose(); }
  return html`<dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.deleteChat}
    onCancel=${event => { event.preventDefault(); close(); }}
    onClick=${event => { if (event.target === dialog.current) close(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${t.deleteChat}</h2><p>${t.deleteChatConfirm}</p>
      ${failed && html`<p class="dialog-failure" role="alert">${t.toolStateFailed}</p>`}
      <footer><button type="button" disabled=${pending} onClick=${close}>${t.cancel}</button>
        <button class="destructive" type="button" disabled=${pending} onClick=${onConfirm}>
          ${pending ? html`<${CircularProgress} size=${20} stroke=${3} label=${t.loading} />` : failed ? t.retry : t.delete}
        </button></footer>
    </section></dialog>`;
}

export function Shell({ onSignedOut }) {
  const state = useSync();
  const snackbarNode = useRef(null);
  const snackbarMotion = useRef(null);
  const nextSnackbar = useRef(state.snackbar);
  nextSnackbar.current = state.snackbar;
  const [snackbar, setSnackbar] = useState(null);
  const [snackbarVisible, setSnackbarVisible] = useState(false);
  useEffect(() => {
    let handoff;
    if (state.snackbar) {
      if (snackbar && snackbarVisible) {
        setSnackbarVisible(false);
        // MainActivity replaces the retained payload halfway through its 400 ms exit.
        handoff = setTimeout(() => {
          setSnackbar(state.snackbar);
          setSnackbarVisible(true);
        }, 200);
      } else {
        setSnackbar(state.snackbar);
        setSnackbarVisible(true);
      }
    } else setSnackbarVisible(false);
    return () => clearTimeout(handoff);
  }, [state.snackbar?.id]);
  useEffect(() => {
    if (!snackbar) return;
    const timer = setTimeout(() => sync.dismissSnackbar(snackbar.id), 4_000);
    return () => clearTimeout(timer);
  }, [snackbar?.id]);
  useLayoutEffect(() => {
    const node = snackbarNode.current;
    if (!node) return;
    const reduced = !!state.display?.reduceMotion;
    const previous = snackbarMotion.current;
    const samePayload = previous?.id === snackbar.id;
    const style = getComputedStyle(node);
    const from = samePayload ? { opacity: style.opacity, transform: style.transform }
      : { opacity: 0, transform: reduced ? "none" : "scale(.8)" };
    previous?.animation.cancel();
    const target = { opacity: snackbarVisible ? 1 : 0,
      transform: reduced ? "none" : `scale(${snackbarVisible ? 1 : .8})` };
    const animation = node.animate([from, target], {
      duration: 400, easing: "cubic-bezier(.4,0,.2,1)", fill: "both",
    });
    snackbarMotion.current = { id: snackbar.id, animation };
    animation.finished.then(() => {
      if (!snackbarVisible && snackbarMotion.current?.animation === animation && !nextSnackbar.current) {
        setSnackbar(null);
      }
    }).catch(() => {});
  }, [snackbar?.id, snackbarVisible, state.display?.reduceMotion]);
  useLayoutEffect(() => () => snackbarMotion.current?.animation.cancel(), []);
  useEffect(() => {
    sync.start(onSignedOut);
    return () => sync.stop();
  }, []);
  const sideBySide = useMediaQuery(SIDE_BY_SIDE_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerPinned, setDrawerPinned] = useState(readDrawerPinned);
  const [composerExpanded, setComposerExpanded] = useState(false);
  const [pageDialog, setPageDialog] = useState(null);
  // The drawer row menu's own dialogs, held here as on the phone they outlive the drawer itself.
  const [rowDialog, setRowDialog] = useState(null);
  const deleteResult = state.conversationDeleteResult;
  useEffect(() => {
    if (pageDialog && (pageDialog.connectionId !== state.connectionId ||
        pageDialog.seq !== sync.interactionTarget()?.seq ||
        (pageDialog.conversationId !== state.openId && !state.switching?.confirmation))) setPageDialog(null);
  }, [state.connectionId, state.openId, state.switching, pageDialog]);
  useEffect(() => { if (pageDialog?.type === "share" && !state.pageAction && !state.shareText) setPageDialog(null); }, [state.pageAction, state.shareText]);
  // A row names the message it forks or shares; the top bar names none and so acts on the whole
  // conversation, which is what the phone's owners do with a null message id.
  function openPageAction(type, messageId = null) {
    const target = { ...sync.attachmentTarget(), conversationId: state.openId, type, messageId };
    const values = messageId == null ? {} : { messageId };
    if (type !== "share" || sync.pageCommand(type, target, values)) setPageDialog(target);
  }
  const shell = useRef(null);
  const drawerTarget = useRef(0);
  const previousWide = useRef(null);
  const drawerAnimation = useRef(null);
  const drawerDrag = useRef(null);
  const dragClick = useRef(null);
  const drawerDismissedComposer = useRef(false);
  const drawerFrame = useRef(0);
  const drawer = useRef(null);
  const menuButton = useRef(null);
  const wasOpen = useRef(false);
  const wasModalOpen = useRef(false);
  const modalOpen = drawerOpen && !sideBySide;
  const reduceMotion = !!state.display?.reduceMotion;
  function observeDrawerProgress(progress) {
    const crossed = progress > 0.5;
    if (crossed && !drawerDismissedComposer.current) {
      shell.current.querySelector(".composer textarea")?.blur();
      setComposerExpanded(false);
    }
    drawerDismissedComposer.current = crossed;
  }
  useLayoutEffect(() => {
    const chat = shell.current.querySelector(".chat");
    const composer = chat.querySelector(".composer-host");
    const capsules = [...chat.querySelectorAll(".top-bar > .capsule")];
    const measure = () => {
      const bounds = chat.getBoundingClientRect();
      const bottoms = capsules.map((node) => node.getBoundingClientRect().bottom);
      // An empty capsule row would measure to -Infinity, which is not a length and would leave every
      // inset unset, so it falls back to a small positive inset instead of poisoning the pair.
      const top = Math.max(...bottoms, bounds.top + 8) - bounds.top + 8;
      const bottom = bounds.bottom - composer.getBoundingClientRect().top;
      chat.style.setProperty("--chat-top-inset", `${top}px`);
      chat.style.setProperty("--chat-bottom-inset", `${bottom}px`);
    };
    const geometry = new ResizeObserver(measure);
    [chat, composer, ...capsules].forEach((node) => geometry.observe(node, { box: "border-box" }));
    measure();
    return () => geometry.disconnect();
  }, []);
  // One interpolated progress drives drawer, scrim and desktop inset; freeze its actual value on takeover.
  function freezeDrawer() {
    const progress = Number(getComputedStyle(shell.current).getPropertyValue("--drawer-progress"));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    drawerAnimation.current?.cancel();
    drawerAnimation.current = null;
    cancelAnimationFrame(drawerFrame.current);
    observeDrawerProgress(progress);
    return progress;
  }
  function settleDrawer(open) {
    const from = freezeDrawer();
    const to = open ? 1 : 0;
    drawerTarget.current = to;
    setDrawerOpen(from > 0 || to > 0);
    if (reduceMotion || from === to) {
      shell.current.style.setProperty("--drawer-progress", String(to));
      observeDrawerProgress(to);
      setDrawerOpen(to > 0);
      return;
    }
    const animation = shell.current.animate(
      [{ "--drawer-progress": String(from) }, { "--drawer-progress": String(to) }],
      { duration: 300, easing: "cubic-bezier(0, 0, 0.2, 1)", fill: "forwards" },
    );
    drawerAnimation.current = animation;
    const observe = () => {
      if (drawerAnimation.current !== animation) return;
      observeDrawerProgress(Number(getComputedStyle(shell.current).getPropertyValue("--drawer-progress")));
      drawerFrame.current = requestAnimationFrame(observe);
    };
    drawerFrame.current = requestAnimationFrame(observe);
    animation.onfinish = () => {
      if (drawerAnimation.current !== animation) return;
      shell.current.style.setProperty("--drawer-progress", String(to));
      animation.cancel();
      drawerAnimation.current = null;
      cancelAnimationFrame(drawerFrame.current);
      observeDrawerProgress(to);
      setDrawerOpen(to > 0);
    };
  }
  function endDrawerDrag(event, cancelled = false) {
    const drag = drawerDrag.current;
    if (!drag || (event && drag.id !== event.pointerId)) return;
    drawerDrag.current = null;
    if (shell.current.hasPointerCapture(drag.id)) shell.current.releasePointerCapture(drag.id);
    if (!drag.accepted) {
      if (drag.interrupted) settleDrawer(drawerTarget.current > 0);
      return;
    }
    dragClick.current = drag.id;
    const progress = freezeDrawer();
    const velocity = event && event.timeStamp - drag.time < 100 ? drag.velocity : 0;
    settleDrawer(cancelled ? drawerTarget.current > 0 : velocity === 0 ? progress >= 0.5 : velocity > 0);
  }
  function beginDrawerDrag(event) {
    dragClick.current = null;
    if (sideBySide || !event.isPrimary || event.button !== 0 ||
        event.target.closest(".detail-sheet-layer, .dropdown, dialog, input, textarea, a, [contenteditable]") ||
        !window.getSelection()?.isCollapsed ||
        (event.pointerType === "mouse" && event.target.closest(".markdown, .user-text"))) return;
    for (let node = event.target; node && node !== shell.current; node = node.parentElement) {
      if (node.scrollWidth > node.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(node).overflowX)) return;
    }
    const interrupted = drawerAnimation.current != null;
    drawerDrag.current = { id: event.pointerId, x: event.clientX, y: event.clientY,
      lastX: event.clientX, time: event.timeStamp, velocity: 0, accepted: false, interrupted, progress: freezeDrawer() };
  }
  function moveDrawerDrag(event) {
    const drag = drawerDrag.current;
    if (!drag || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (!drag.accepted) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 8) return;
      if (Math.abs(dy) >= Math.abs(dx)) { endDrawerDrag(event, true); return; }
      drag.accepted = true;
      shell.current.setPointerCapture(event.pointerId);
    }
    const elapsed = event.timeStamp - drag.time;
    if (elapsed > 0) drag.velocity = (event.clientX - drag.lastX) / elapsed;
    drag.lastX = event.clientX;
    drag.time = event.timeStamp;
    const progress = Math.max(0, Math.min(1, drag.progress + dx / drawer.current.clientWidth));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    observeDrawerProgress(progress);
    setDrawerOpen(progress > 0);
    event.preventDefault();
  }
  useLayoutEffect(() => {
    const node = shell.current;
    const stopOwnedTouch = (event) => { if (drawerDrag.current?.accepted) event.preventDefault(); };
    const onResize = () => endDrawerDrag(null, true);
    node.addEventListener("touchmove", stopOwnedTouch, { passive: false });
    window.addEventListener("resize", onResize);
    return () => {
      node.removeEventListener("touchmove", stopOwnedTouch);
      window.removeEventListener("resize", onResize);
    };
  }, [sideBySide, reduceMotion]);
  useEffect(() => () => { drawerAnimation.current?.cancel(); cancelAnimationFrame(drawerFrame.current); }, []);
  // Wide/narrow state machine: entering wide restores the expansion from the persisted pin,
  // entering narrow always collapses the overlay and leaves the pin untouched. A same-mode
  // re-run (motion setting change) just re-settles at the current expansion.
  useLayoutEffect(() => {
    endDrawerDrag(null, true);
    const was = previousWide.current;
    previousWide.current = sideBySide;
    if (was === null) {
      const open = sideBySide && readDrawerPinned();
      drawerTarget.current = open ? 1 : 0;
      shell.current.style.setProperty("--drawer-progress", open ? "1" : "0");
      setDrawerOpen(open);
      return;
    }
    if (was === sideBySide) { settleDrawer(drawerTarget.current > 0); return; }
    settleDrawer(sideBySide && readDrawerPinned());
  }, [sideBySide, reduceMotion]);
  // ChatTopBar falls back to the brand while the title is blank.
  const openTitle = state.conversations.find((c) => c.id === state.openId)?.title?.trim() || null;
  // ChatTopBar stacks the token subtitle under a conversation title and hides it with the brand
  // title, or while there is nothing in the window yet (`contextAvailable = totalTokens > 0`). The
  // figure is the composer ring's own: this connection's projection, kept while the next one prices.
  const contextSubtitle = openTitle && (state.context?.estimatedTokens ?? 0) > 0
    ? t.contextUsage(state.context.estimatedLabel, state.context.budgetLabel) : null;

  useLayoutEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!drawerOpen && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = drawerOpen;
    const enteringModal = modalOpen && !wasModalOpen.current;
    wasModalOpen.current = modalOpen;
    if (!modalOpen) return undefined;
    // The panel itself takes focus on entry; focusing a control here would focus the search
    // field first, which on a phone opens the soft keyboard without the user asking for it.
    if (enteringModal) drawer.current?.focus();
    const onKey = (event) => {
      if (event.defaultPrevented || shell.current.querySelector(".detail-sheet-layer, .dropdown, dialog[open]")) return;
      if (event.key === "Escape") { event.preventDefault(); settleDrawer(false); }
      if (event.key === "Tab") {
        const controls = [...drawer.current.querySelectorAll("input:not([disabled]), button:not([disabled])")];
        const first = controls[0] ?? drawer.current;
        const last = controls.at(-1) ?? drawer.current;
        if (!drawer.current.contains(document.activeElement) ||
            (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
          event.preventDefault();
          (event.shiftKey ? last : first).focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen, modalOpen, reduceMotion]);

  const shellClass = ["shell", drawerOpen && "drawer-open", sideBySide ? "side-by-side" : "modal"]
    .filter(Boolean).join(" ");
  return html`
    <div class=${shellClass} ref=${shell} data-blur-effects=${state.display?.blurEffectsEnabled == null ? null : String(state.display.blurEffectsEnabled)}
      data-reduce-motion=${state.display?.reduceMotion == null ? null : String(state.display.reduceMotion)}
      onPointerDown=${beginDrawerDrag} onPointerMove=${moveDrawerDrag}
      onPointerUp=${(event) => endDrawerDrag(event)} onPointerCancel=${(event) => endDrawerDrag(event, true)}
      onLostPointerCapture=${(event) => { if (event.target === shell.current) endDrawerDrag(event, true); }}
      onClickCapture=${(event) => {
        if (event.pointerId === dragClick.current) { event.preventDefault(); event.stopPropagation(); dragClick.current = null; }
      }}>
      <aside id="drawer" class="drawer" ref=${drawer} aria-label=${t.conversations} tabindex="-1"
        role=${sideBySide ? null : "dialog"} aria-modal=${modalOpen ? "true" : null}
        inert=${!drawerOpen}>
        <${DrawerContent} conversations=${state.conversations} openId=${state.openId}
          connected=${state.connected} loading=${state.listLoading} hasMore=${state.listHasMore} reduceMotion=${reduceMotion}
          switching=${!!state.switching || state.openStatus === "loading"}
          searchQuery=${state.searchQuery} searchResults=${state.searchResults} searchLoading=${state.searchLoading} searchFailed=${state.searchFailed}
          onRequestRename=${(request) => setRowDialog({ ...request, type: "rename" })}
          onRequestDelete=${(request) => setRowDialog({ ...request, type: "delete" })}
          onSelect=${(id) => { sync.open(id); if (!sideBySide) settleDrawer(false); }} />
      </aside>
      <div class="scrim" aria-hidden="true" onClick=${() => settleDrawer(false)}></div>
      <main class=${`chat ${composerExpanded ? "chat-composer-expanded" : ""}`} inert=${modalOpen}>
        <${TopBar} title=${openTitle} subtitle=${contextSubtitle} conversationId=${state.openId} drawerOpen=${drawerOpen} menuButton=${menuButton} reduceMotion=${reduceMotion} onSignedOut=${onSignedOut} connected=${state.connected}
          generating=${!!state.generating}
          search=${state.conversationSearch}
          onSystemPrompt=${() => openPageAction("system_prompt")}
          promptEnabled=${state.connected && !!state.composer?.systemPrompt && !state.composer?.controls?.lowContextModeEnabled && !state.pendingAction}
          onFork=${() => openPageAction("fork")} onShare=${() => openPageAction("share")} pageBusy=${!!state.pageAction}
          onToggleDrawer=${() => {
            if (!sideBySide) { settleDrawer(drawerTarget.current === 0); return; }
            // In the wide window the button toggles the persisted pin, not a temporary overlay.
            const next = !drawerPinned;
            setDrawerPinned(next);
            try { localStorage.setItem(DRAWER_PINNED_KEY, String(next)); } catch (_) {}
            settleDrawer(next);
          }} />
        <${MessageList} state=${state} label=${openTitle || t.newChat} onPageAction=${openPageAction} />
        <div class="chat-top-blur" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
        <${Composer} state=${state} MoreMenu=${MoreMenu} expanded=${composerExpanded} onExpandedChange=${setComposerExpanded} />
        ${snackbar && html`<div class="chat-snackbar-host" inert=${!snackbarVisible}>
          <div class="chat-snackbar" ref=${snackbarNode} role=${snackbarVisible ? "status" : null}
            aria-hidden=${!snackbarVisible}>
            <span class="chat-snackbar-text">${snackbar.message}</span>
            <button class="chat-snackbar-close" type="button" aria-label=${t.cancel}
              onClick=${() => sync.dismissSnackbar(snackbar.id)}>${icon(ICON_CLOSE)}</button>
          </div></div>`}
        ${pageDialog && html`<${ConversationDialog} state=${state} request=${pageDialog} onClose=${() => setPageDialog(null)} />`}
      </main>
      ${rowDialog?.type === "rename" && html`<${ConversationRenameDialog} conversation=${rowDialog} onClose=${() => setRowDialog(null)} />`}
      ${rowDialog?.type === "delete" && html`<${ConversationDeleteDialog} actionId=${rowDialog.actionId}
        complete=${rowDialog.actionId != null && deleteResult?.actionId === rowDialog.actionId && deleteResult.success}
        pending=${rowDialog.actionId != null && state.conversationDelete?.actionId === rowDialog.actionId}
        failed=${rowDialog.failed === true ||
          (rowDialog.actionId != null && deleteResult?.actionId === rowDialog.actionId && !deleteResult.success)}
        onConfirm=${() => {
          const command = sync.deleteConversation(rowDialog.conversationId);
          setRowDialog(command ? { ...rowDialog, actionId: command.actionId, failed: false } : { ...rowDialog, failed: true });
        }}
        onClose=${() => setRowDialog(null)} />`}
    </div>`;
}
