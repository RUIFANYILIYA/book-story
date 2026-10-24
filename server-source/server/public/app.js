/**
 * The admin console.
 *
 * Deliberately framework-free and build-free: this file is served as-is, so there is no toolchain
 * between the source and what the browser runs, and a NAS operator can read it. It talks only to
 * the published /api/v1 surface — if a screen here cannot be built from those endpoints, the API is
 * missing something the Android app will need too, and that is worth discovering now.
 */

const TOKEN_KEY = 'ebookd.access';
const REFRESH_KEY = 'ebookd.refresh';

const state = {
  access: localStorage.getItem(TOKEN_KEY) ?? '',
  refresh: localStorage.getItem(REFRESH_KEY) ?? '',
  me: null,
  cursor: null,
  hasMore: false,
  books: [],
  selected: new Set(),
  batching: false,
  categories: [],
  folderBooks: [],
  collections: [],
  tags: [],
};

const $ = (sel) => document.querySelector(sel);
const el = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  for (const child of children.flat()) {
    if (child === null || child === undefined) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
};

/* ---------------------------------------------------------------- dialogs
 * window.prompt/confirm are the browser's own dialogs: unstyled, blocking the tab, and suppressible
 * by the browser's "prevent this page from creating additional dialogs" — after which every action
 * here silently does nothing. These helpers keep the same call shape (await askText / askConfirm)
 * on top of a real element.
 */

let modalResolve = null;

function openModal({ title, text = '', value = null, secret = false, okText = '确定', danger = false }) {
  return new Promise((resolve) => {
    modalResolve = resolve;

    $('#modal-title').textContent = title;
    const textNode = $('#modal-text');
    textNode.textContent = text;
    textNode.hidden = !text;

    const input = $('#modal-input');
    input.hidden = value === null;
    input.type = secret ? 'password' : 'text';
    input.value = value ?? '';

    const ok = $('#modal-ok');
    ok.textContent = okText;
    ok.classList.toggle('danger', danger);
    const syncOk = () => {
      ok.disabled = !input.hidden && input.value.trim().length === 0;
    };
    input.oninput = syncOk;
    syncOk();

    $('#modal').hidden = false;
    if (value !== null) {
      input.focus();
      input.select();
    } else {
      ok.focus();
    }
  });
}

function closeModal(accepted) {
  $('#modal').hidden = true;
  // A three-way dialog hides the single-answer row; restore it for the next question whichever
  // kind this one was, or the next askText would open with no visible buttons.
  $('#modal-choices').hidden = true;
  $('#modal-actions').hidden = false;
  const resolve = modalResolve;
  modalResolve = null;
  if (resolve) resolve(accepted);
}

async function askText({ title, text = '', value = '', secret = false, okText = '确定' }) {
  const accepted = await openModal({ title, text, value, secret, okText });
  if (!accepted) return null;
  const raw = $('#modal-input').value;
  // Passwords keep their spaces; names and labels do not.
  return secret ? raw : raw.trim();
}

function askConfirm({ title, text = '', okText = '删除', danger = true }) {
  return openModal({ title, text, okText, danger });
}

/**
 * A question with up to three answers, stacked — the upload name clash.
 *
 * Enter deliberately picks nothing: the first choice is the destructive one ("覆盖"), and a
 * keypress that lands while the operator is still reading must not decide it for them. Esc and
 * a click on the scrim both dismiss, which resolves null and cancels the upload.
 */
function askChoice({ title, text = '', choices }) {
  return new Promise((resolve) => {
    modalResolve = resolve;
    $('#modal-title').textContent = title;
    const textNode = $('#modal-text');
    textNode.textContent = text;
    textNode.hidden = !text;
    $('#modal-input').hidden = true;
    $('#modal-actions').hidden = true;

    const box = $('#modal-choices');
    box.replaceChildren(
      ...choices.map((choice) =>
        el('button', {
          className: choice.danger ? 'danger' : 'ghost',
          onclick: () => closeModal(choice.id),
        }, choice.label),
      ),
    );
    box.hidden = false;
    $('#modal').hidden = false;
    box.firstElementChild?.focus();
  });
}

let toastTimer = null;
function toast(message, bad = false) {
  const node = $('#toast');
  node.textContent = message;
  node.classList.toggle('bad', bad);
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    node.hidden = true;
  }, bad ? 5000 : 2500);
}

/** One refresh in flight at a time; two tabs racing would otherwise invalidate each other. */
let refreshing = null;

async function refreshToken() {
  if (!state.refresh) throw new Error('no refresh token');
  refreshing ??= (async () => {
    const res = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ refreshToken: state.refresh }),
    });
    if (!res.ok) throw new Error('refresh failed');
    const body = await res.json();
    setTokens(body.accessToken, body.refreshToken);
    return body.accessToken;
  })().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

function setTokens(access, refresh) {
  state.access = access;
  state.refresh = refresh;
  localStorage.setItem(TOKEN_KEY, access);
  localStorage.setItem(REFRESH_KEY, refresh);
}

function clearTokens() {
  state.access = '';
  state.refresh = '';
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_KEY);
}

/**
 * Every call goes through here so that a 401 is handled once: refresh, replay, and only sign the
 * operator out if the refresh itself fails. Handling it per call site is how a console ends up
 * showing a random error toast instead of the login screen.
 */
async function api(path, options = {}, alreadyRetried = false) {
  const headers = { ...(options.headers ?? {}) };
  if (state.access) headers.authorization = `Bearer ${state.access}`;
  if (options.body !== undefined && !(options.body instanceof FormData)) {
    headers['content-type'] = 'application/json';
    options.body = JSON.stringify(options.body);
  }

  const res = await fetch(path, { ...options, headers });
  if (res.status === 401 && !alreadyRetried && state.refresh) {
    try {
      await refreshToken();
      return api(path, options, true);
    } catch {
      clearTokens();
      showLogin();
      throw new Error('会话已过期，请重新登录');
    }
  }
  if (res.status === 204) return null;
  const text = await res.text();
  const body = text.length > 0 ? JSON.parse(text) : null;
  if (!res.ok) {
    const error = new Error(body?.error?.message ?? `请求失败 (${res.status})`);
    error.code = body?.error?.code;
    error.status = res.status;
    error.details = body?.error?.details;
    throw error;
  }
  return body;
}

// ---------------------------------------------------------------- views

/**
 * Cover images need the Authorization header, and `<img src>` cannot send one — an authenticated
 * cover endpoint and a plain img tag are simply incompatible. The options were to make covers
 * public (no: a personal cover override is per-user private data), to put the token in the query
 * (no: query strings land in logs and referrers), or to fetch the bytes and hand the browser an
 * object URL. This is the last one.
 *
 * The Android app does the same thing through its OkHttp interceptor, so the endpoint keeps its
 * per-user semantics without a special case on either client.
 */
const coverCache = new Map(); // coverUrl -> Promise<objectURL | null>
let coverQueue = Promise.resolve();

/**
 * Always returns a promise, and the cache only ever holds promises.
 *
 * Storing the resolved string alongside in-flight promises looked harmless and is not: the second
 * caller then receives a bare string and `.then` is undefined. One value type, one contract.
 */
function loadCover(url) {
  const cached = coverCache.get(url);
  if (cached !== undefined) return cached;

  // Serialized: a shelf of forty covers fired at once would open forty sockets and, on a NAS over
  // a slow link, starve the metadata requests that make the page usable.
  const task = coverQueue.then(async () => {
    try {
      const res = await fetch(url, { headers: state.access ? { authorization: `Bearer ${state.access}` } : {} });
      if (!res.ok) return null;
      const objectUrl = URL.createObjectURL(await res.blob());
      urlsToRelease.add(objectUrl);
      return objectUrl;
    } catch {
      return null;
    }
  });
  coverQueue = task.catch(() => undefined);
  coverCache.set(url, task);
  return task;
}

const urlsToRelease = new Set();

function releaseCovers() {
  for (const url of urlsToRelease) URL.revokeObjectURL(url);
  urlsToRelease.clear();
  coverCache.clear();
  coverQueue = Promise.resolve();
}

function showLogin() {
  $('#view-login').hidden = false;
  for (const view of document.querySelectorAll('.view')) {
    if (view.id !== 'view-login') view.hidden = true;
  }
  $('#tabs').hidden = true;
  $('#logout').hidden = true;
  $('#whoami').textContent = '';
}

async function showApp() {
  state.me = await api('/api/v1/auth/me');
  $('#view-login').hidden = true;
  $('#tabs').hidden = false;
  $('#logout').hidden = false;
  $('#whoami').textContent = `${state.me.user.displayName}（${state.me.user.role}）`;
  await refreshTaxonomy();
  await switchView('shelf');
}

async function switchView(name) {
  for (const tab of document.querySelectorAll('.tab')) tab.classList.toggle('active', tab.dataset.view === name);
  for (const view of document.querySelectorAll('.view')) view.hidden = view.id !== `view-${name}`;
  if (name === 'shelf') await loadShelf(true);
  if (name === 'organize') renderTaxonomy();
  if (name === 'users') await loadUsers();
  if (name === 'status') await loadStatus();
  if (name === 'account') renderAccount();
}

function renderAccount() {
  const user = state.me?.user ?? {};
  $('#account-form [name=username]').value = user.username ?? '';
  $('#account-form [name=displayName]').value = user.displayName ?? '';
  $('#password-form').reset();
}

function wireAccount() {
  $('#account-form').onsubmit = async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    const username = String(form.get('username') ?? '').trim();
    const displayName = String(form.get('displayName') ?? '').trim();
    const patch = { displayName };
    const renamed = username !== state.me.user.username;
    if (username && renamed) patch.username = username;

    try {
      state.me.user = await api('/api/v1/auth/me', { method: 'PATCH', body: patch }).then((r) => r.user);
      $('#whoami').textContent = `${state.me.user.displayName}（${state.me.user.role}）`;
      renderAccount();
      toast(renamed ? '已改名，下次请用新用户名登录' : '已保存');
    } catch (error) {
      toast(error.message ?? '保存失败', true);
    }
  };

  $('#password-form').onsubmit = async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    try {
      const res = await api('/api/v1/auth/me/password', {
        method: 'POST',
        body: {
          currentPassword: form.get('currentPassword'),
          newPassword: form.get('newPassword'),
        },
      });
      $('#password-form').reset();
      state.me.user.mustChangePassword = false;
      toast(res.otherSessionsRevoked ? `已修改，其它 ${res.otherSessionsRevoked} 个会话已登出` : '已修改');
    } catch (error) {
      toast(error.message ?? '修改失败', true);
    }
  };
}

// ---------------------------------------------------------------- shelf

async function loadShelf(reset) {
  if (reset) {
    state.cursor = null;
    state.books = [];
    $('#shelf').replaceChildren();
  }
  const params = new URLSearchParams({ limit: '40', sort: $('#sort').value, order: $('#sort').value === 'title' ? 'asc' : 'desc' });
  const q = $('#search').value.trim();
  if (q) params.set('q', q);
  const category = $('#filter-category').value;
  if (category) {
    const found = state.categories.find((c) => c.id === category);
    if (found) params.set('category', found.path);
  }
  const collection = $('#filter-collection').value;
  if (collection) params.set('collection', collection);
  if ($('#filter-review').checked) params.set('needsReview', 'true');
  if (state.cursor) params.set('cursor', state.cursor);

  const page = await api(`/api/v1/books?${params}`);
  state.books = reset ? page.items : [...state.books, ...page.items];
  state.cursor = page.nextCursor;
  state.hasMore = page.hasMore;

  // A shelf page draws its sub-shelves as folder tiles, and a tile needs the books inside it for
  // the preview cover. One extra request, only when a real shelf is open: "全部" has no folders.
  const shelf = currentShelf();
  state.folderBooks = shelf.root && !shelf.sub ? await loadFolderBooks(shelf.root) : [];

  renderShelf();
  renderShelfTabs();
  renderShelfCrumbs();
}

function renderShelf() {
  const grid = $('#shelf');
  const { root, sub } = currentShelf();
  const folders = root && !sub
    ? state.categories
        .filter((c) => c.parent_id === root.id)
        .map((child) => folderCard(child, state.folderBooks.filter((b) => b.categoryId === child.id)))
    : [];

  grid.replaceChildren(...folders, ...state.books.map(bookCard));
  $('#more').hidden = !state.hasMore;
  $('#shelf-count').textContent = `${state.books.length} 本${state.hasMore ? '（还有更多）' : ''}`;
  $('#batch-toggle').disabled = state.books.length === 0;
}

/**
 * The shelf the grid is showing, derived from the category filter so the tabs, the breadcrumb and
 * the folder tiles can never disagree with what is actually listed.
 */
function currentShelf() {
  const found = state.categories.find((c) => c.id === $('#filter-category').value);
  if (!found) return { root: null, sub: null };
  if (!found.parent_id) return { root: found, sub: null };
  return { root: state.categories.find((c) => c.id === found.parent_id) ?? null, sub: found };
}

async function loadFolderBooks(root) {
  // The whole subtree in one page: the counts and the preview covers have to be exact, and a shelf
  // that needs more than 200 books just to draw its folders is beyond what this console is for.
  const params = new URLSearchParams({ limit: '200', sort: 'title', order: 'asc', category: root.path });
  const page = await api(`/api/v1/books?${params}`);
  return page.items;
}

function selectShelf(id) {
  $('#filter-category').value = id ?? '';
  loadShelf(true);
}

function renderShelfTabs() {
  const { root } = currentShelf();
  // A shelf counts its own books plus everything filed one level down — the same number the app
  // shows on its tab.
  const total = (category) =>
    (category.book_count ?? 0) +
    state.categories
      .filter((c) => c.parent_id === category.id)
      .reduce((sum, c) => sum + (c.book_count ?? 0), 0);

  // Drawn in `position` order, which is the order the drag below writes back.
  const roots = state.categories
    .filter((c) => !c.parent_id)
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));

  $('#shelf-tabs').replaceChildren(
    el('button', { className: `shelf-tab${root ? '' : ' active'}`, onclick: () => selectShelf(null) }, '全部'),
    ...roots.map((c) => {
      const tab = el('button', {
        className: `shelf-tab${root?.id === c.id ? ' active' : ''}`,
        title: '拖动可调整书架顺序',
        onclick: () => {
          // A drag ends with a click on the tab it started on; that would also switch shelves.
          if (suppressTabClick) return;
          selectShelf(c.id);
        },
      }, c.name, el('span', { className: 'tab-count' }, String(total(c))));
      tab.dataset.id = c.id;
      return tab;
    }),
  );

  renderCreateMenu();
}

/* ---------------------------------------------------------------- reordering the shelf tabs
 * The tabs are the app's shelf pages, so they are ordered the way the app orders them: drag one
 * sideways and the whole level is rewritten in one request. The arrows on the 分类 page stay for
 * single steps, and for touch, where a horizontal drag belongs to the scrolling strip.
 */

let tabDrag = null;
let suppressTabClick = false;

function wireShelfTabDrag() {
  const strip = $('#shelf-tabs');

  strip.addEventListener('pointerdown', (event) => {
    const tab = event.target.closest('.shelf-tab[data-id]');
    if (!tab || event.button !== 0 || event.pointerType === 'touch') return;

    const tabs = [...strip.querySelectorAll('.shelf-tab[data-id]')];
    tabDrag = {
      tab,
      tabs,
      pointerId: event.pointerId,
      startX: event.clientX,
      startIndex: tabs.indexOf(tab),
      insertAt: tabs.indexOf(tab),
      active: false,
    };
    tab.setPointerCapture(event.pointerId);
  });

  strip.addEventListener('pointermove', (event) => {
    const drag = tabDrag;
    if (!drag || event.pointerId !== drag.pointerId) return;

    const dx = event.clientX - drag.startX;
    if (!drag.active) {
      // A few pixels of slack, so a click with a shaky hand is still a click.
      if (Math.abs(dx) < 6) return;
      drag.active = true;
      drag.tab.classList.add('dragging');
      strip.classList.add('reordering');
    }

    drag.tab.style.transform = `translateX(${dx}px)`;

    // Counted among the other tabs: that number is both where the indicator goes and where the tab
    // lands once it is lifted out of the row.
    const others = drag.tabs.filter((t) => t !== drag.tab);
    let insertAt = others.length;
    for (let i = 0; i < others.length; i += 1) {
      const rect = others[i].getBoundingClientRect();
      if (event.clientX < rect.left + rect.width / 2) {
        insertAt = i;
        break;
      }
    }
    drag.insertAt = insertAt;
    markTabDropSlot(others, insertAt);

    // Past either end the strip scrolls, so a shelf can travel further than the visible width.
    const bounds = strip.getBoundingClientRect();
    if (event.clientX > bounds.right - 36) strip.scrollLeft += 12;
    else if (event.clientX < bounds.left + 36) strip.scrollLeft -= 12;
  });

  const endDrag = (event) => {
    const drag = tabDrag;
    if (!drag || event.pointerId !== drag.pointerId) return;
    tabDrag = null;

    drag.tab.classList.remove('dragging');
    drag.tab.style.transform = '';
    strip.classList.remove('reordering');
    clearTabDropSlot();
    if (!drag.active) return;

    // The click that follows pointerup would switch shelves as a side effect of reordering. The
    // timer runs after the click has been dispatched, which is what makes this a one-shot.
    suppressTabClick = true;
    setTimeout(() => {
      suppressTabClick = false;
    }, 0);

    if (drag.insertAt === drag.startIndex) return;
    const order = drag.tabs.filter((t) => t !== drag.tab).map((t) => t.dataset.id);
    order.splice(drag.insertAt, 0, drag.tab.dataset.id);
    void applyShelfOrder(order);
  };

  strip.addEventListener('pointerup', endDrag);
  strip.addEventListener('pointercancel', endDrag);
}

function markTabDropSlot(others, insertAt) {
  clearTabDropSlot();
  if (others.length === 0) return;
  if (insertAt >= others.length) others[others.length - 1].classList.add('drop-after');
  else others[insertAt].classList.add('drop-before');
}

function clearTabDropSlot() {
  for (const node of $('#shelf-tabs').querySelectorAll('.drop-before, .drop-after')) {
    node.classList.remove('drop-before', 'drop-after');
  }
}

async function applyShelfOrder(ids) {
  try {
    await api('/api/v1/categories/reorder', { method: 'POST', body: { parentId: null, ids } });
    await refreshTaxonomy();
    renderTaxonomy();
    await loadShelf(true);
  } catch (err) {
    // Put the row back the way the server still has it, then say why.
    await refreshTaxonomy().catch(() => undefined);
    renderTaxonomy();
    toast(err.message, true);
  }
}

/**
 * The quick-create menu follows the shelf you are looking at: a sub-shelf has to hang from
 * something, so that entry is only live once a real shelf is open, and its label says which one.
 */
function renderCreateMenu() {
  const { root } = currentShelf();
  const item = $('#create-sub-shelf');
  if (!item) return;
  item.disabled = !root;
  item.textContent = root ? `在「${root.name}」下新建子书架` : '新建子书架（先进入一个书架）';
}

async function createCategoryQuick(parent) {
  const name = await askText({
    title: parent ? `在「${parent.name}」下新建子书架` : '新建书架',
    value: '',
    okText: '创建',
  });
  if (!name) return;

  try {
    const created = await api('/api/v1/categories', {
      method: 'POST',
      body: { name: name.trim(), parentId: parent?.id ?? null },
    });
    await refreshTaxonomy();
    renderTaxonomy();

    // Jump straight into what was just made: creating a shelf and then having to hunt for it is the
    // whole reason this button exists.
    if (created?.category?.id) selectShelf(created.category.id);
    else await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

function renderShelfCrumbs() {
  const { root, sub } = currentShelf();
  const node = $('#shelf-crumbs');
  if (!sub || !root) {
    node.hidden = true;
    node.replaceChildren();
    return;
  }
  node.hidden = false;
  node.replaceChildren(
    el('button', { className: 'crumb', onclick: () => selectShelf(root.id) }, `← ${root.name}`),
    el('span', { className: 'crumb-sep' }, '/'),
    el('span', { className: 'crumb current' }, sub.name),
  );
}

/**
 * A sub-shelf drawn the way the app draws it: a folder of books occupying one grid cell, the first
 * cover on top, the count in the corner. Clicking it opens the sub-shelf, exactly like the app.
 */
/**
 * A sub-shelf as one grid cell: a 3×3 wall of the covers inside it.
 *
 * It used to be a stack of two sheets with a single cover on top, which read as "one book with a
 * shadow" from a normal viewing distance — the tile is the same size as a book, so the only signal
 * that it held several was the count badge. Nine covers make the tile unmistakably a container, and
 * the empty slots keep the 3×3 shape honest when the sub-shelf holds fewer than nine.
 *
 * The cell is 2:3, so each of the nine slots is 2:3 as well: the same shape as a book cover.
 */
function folderCard(category, books) {
  const slots = [];
  for (let index = 0; index < 9; index += 1) {
    const book = books[index];
    const slot = el('div', { className: 'folder-slot' });
    if (book) {
      if (book.hasCover) {
        // Painted as a background rather than an <img>: nine images per folder would each need
        // their own object URL swapped in, and a background is one assignment.
        loadCover(`${book.coverUrl}?t=${encodeURIComponent(book.coverUpdatedAt ?? book.contentUpdatedAt)}`)
          .then((objectUrl) => {
            if (objectUrl) slot.style.backgroundImage = `url("${objectUrl}")`;
          })
          .catch(() => undefined);
      } else {
        slot.classList.add('fallback');
        slot.textContent = book.title.slice(0, 1);
      }
    } else {
      slot.classList.add('empty');
    }
    slots.push(slot);
  }

  const card = el('div', { className: 'item folder' },
    el('div', { className: 'folder-grid' },
      ...slots,
      el('span', { className: 'folder-count' }, `${category.book_count ?? books.length} 本`),
    ),
    el('div', { className: 'meta' },
      el('div', { className: 'title' }, category.name),
      el('div', { className: 'sub' }, '子书架'),
    ),
    el('div', { className: 'folder-actions' },
      el('button', {
        className: 'ghost',
        onclick: (event) => {
          event.stopPropagation();
          renameCategory(category);
        },
      }, '改名'),
      el('button', {
        className: 'ghost danger',
        onclick: (event) => {
          event.stopPropagation();
          deleteCategory(category);
        },
      }, '删除'),
    ),
  );
  card.onclick = () => openSubShelfSheet(category);
  return card;
}

/*
 * Opening a folder.
 *
 * It used to swap the grid over to the folder's books and grow a breadcrumb, which made a folder
 * feel like a page you navigate to. It is not — it is a smaller shelf — so it now arrives as a
 * panel that slides up over the shelf, and closing it leaves the shelf exactly as it was.
 */

let subShelfToken = 0;

async function openSubShelfSheet(category) {
  const sheet = $('#sub-shelf');
  const body = $('#sub-shelf-books');
  const token = ++subShelfToken;

  $('#sub-shelf-title').textContent = category.name;
  $('#sub-shelf-count').textContent = '载入中…';
  body.replaceChildren();

  sheet.hidden = false;
  document.body.classList.add('sub-shelf-open');
  // One forced layout, so the closed position is painted before the class that animates away from
  // it; without this the panel appears already open.
  void sheet.offsetHeight;
  sheet.classList.add('open');

  try {
    const params = new URLSearchParams({
      limit: '200',
      sort: 'title',
      order: 'asc',
      category: category.path,
    });
    const page = await api(`/api/v1/books?${params}`);
    // A slow request that lands after the reader closed the panel (or opened another folder) must
    // not paint into the wrong one.
    if (token !== subShelfToken) return;
    $('#sub-shelf-count').textContent = `${page.items.length} 本`;
    body.replaceChildren(
      ...(page.items.length > 0
        ? page.items.map(bookCard)
        : [el('p', { className: 'muted sub-shelf-empty' }, '这个子书架还没有书')]),
    );
  } catch (err) {
    if (token !== subShelfToken) return;
    $('#sub-shelf-count').textContent = '';
    body.replaceChildren(el('p', { className: 'muted sub-shelf-empty' }, err.message));
  }
}

function closeSubShelfSheet() {
  const sheet = $('#sub-shelf');
  if (sheet.hidden) return;
  // Invalidate any request still in flight for this panel.
  subShelfToken += 1;
  sheet.classList.remove('open');
  // The slide-down has to finish before the element leaves the layout, or it would just vanish.
  setTimeout(() => {
    if (!sheet.classList.contains('open')) {
      sheet.hidden = true;
      document.body.classList.remove('sub-shelf-open');
    }
  }, 260);
}

function bookCard(book) {
  const cover = el('div', { className: 'cover' });
  if (book.hasCover) {
    // Fetched with the auth header; see loadCover for why a plain <img src> cannot work here.
    loadCover(`${book.coverUrl}?t=${encodeURIComponent(book.coverUpdatedAt ?? book.contentUpdatedAt)}`)
      .then((objectUrl) => {
        if (!objectUrl) return;
        cover.replaceWith(el('img', { className: 'cover', loading: 'lazy', alt: '', src: objectUrl }));
      })
      .catch(() => undefined);
  }

  const badges = el('div', { className: 'badges' },
    ...book.formats.map((f) => el('span', { className: 'badge' }, f)),
    book.needsReview ? el('span', { className: 'badge warn' }, '待确认') : null,
    book.my.status === 'reading' ? el('span', { className: 'badge' }, '在读') : null,
  );

  const pick = el('div', { className: 'pick' },
    el('input', {
      type: 'checkbox',
      checked: state.selected.has(book.id),
      onclick: (event) => {
        event.stopPropagation();
        if (event.target.checked) state.selected.add(book.id);
        else state.selected.delete(book.id);
        updateBatchBar();
      },
    }),
  );

  const card = el('div', { className: `item${state.selected.has(book.id) ? ' selected' : ''}` },
    // The badges sit on the cover, in the corner the app uses for reading progress: a shelf that
    // grows an extra line of chips under every title stops looking like the shelf on the phone.
    el('div', { className: 'cover-frame' },
      cover,
      badges,
      // A hover affordance, so opening the reader does not require the detail drawer first.
      el('button', {
        className: 'read-chip',
        onclick: (event) => {
          event.stopPropagation();
          void openReader(book.id);
        },
      }, '阅读'),
      // And one for removing the book, so deleting a single title does not require entering batch
      // mode. Only rendered for callers holding book:delete — the batch bar is the fallback for
      // everyone else, and an affordance that answers 403 is worse than no affordance.
      state.me?.capabilities?.includes('book:delete')
        ? el('button', {
            className: 'delete-chip',
            onclick: (event) => {
              event.stopPropagation();
              void deleteBook(book);
            },
          }, '删除')
        : null,
    ),
    el('div', { className: 'meta' },
      el('div', { className: 'title' }, book.title),
      el('div', { className: 'sub' }, book.authors.join(' / ') || '未知作者'),
    ),
    state.batching ? pick : null,
  );
  card.onclick = () => {
    if (state.batching) {
      if (state.selected.has(book.id)) state.selected.delete(book.id);
      else state.selected.add(book.id);
      renderShelf();
      updateBatchBar();
    } else {
      openDetail(book.id);
    }
  };
  return card;
}

function updateBatchBar() {
  $('#batch-count').textContent = `已选 ${state.selected.size} 本`;
  const any = state.selected.size > 0;
  for (const button of document.querySelectorAll('[data-batch]')) button.disabled = !any;
}

async function runBatch(kind) {
  const bookIds = [...state.selected];
  if (bookIds.length === 0) return;
  let path = `/api/v1/books/batch/${kind}`;
  let body = { bookIds };

  if (kind === 'tag') {
    const names = $('#batch-tags').value.split(',').map((s) => s.trim()).filter(Boolean);
    if (names.length === 0) return toast('先填写标签', true);
    body.add = names;
  } else if (kind === 'collection') {
    const id = $('#batch-collection').value;
    if (!id) return toast('先选择分组', true);
    path = '/api/v1/books/batch/collection';
    body.add = [id];
    body.remove = [];
  } else if (kind === 'category') {
    const id = $('#batch-category').value;
    if (!id) return toast('先选择分类', true);
    body.categoryId = id;
  }

  if (
    kind === 'delete' &&
    !(await askConfirm({
      title: `删除 ${bookIds.length} 本书`,
      text: '文件会在保留期内继续可下载，之后才被回收。',
    }))
  ) {
    return;
  }

  try {
    const result = await api(path, { method: 'POST', body });
    // A skipped id is a book another device removed between selection and submit, which is
    // expected; saying so beats reporting a clean success over a partial one.
    toast(`已处理 ${result.applied} 本${result.skipped ? `，跳过 ${result.skipped} 本` : ''}`, result.skipped > 0);
    state.selected.clear();
    updateBatchBar();
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

// ---------------------------------------------------------------- reader

/*
 * Reading a book in the console.
 *
 * The server stores books as files, so reading online is: take the primary readable file, cut it
 * into chapters, and keep exactly one chapter in the DOM. A 9 MB novel is a few hundred thousand
 * paragraphs; laying all of them out at once is what makes browser-based readers freeze, and
 * nothing is gained because a reader only ever sees a page or two.
 *
 * Two formats reach this reader. TXT is downloaded here and split on heading lines. EPUB cannot be
 * unzipped in the page without shipping a zip reader to it, so the server walks the spine, rebuilds
 * each document from a whitelist of tags, and returns chapters — which is also why the HTML in
 * `chapter.html` can be trusted into the DOM: nothing from the file survives the walk verbatim.
 *
 * Progress is written back to `PUT /api/v1/progress/:bookId` with a character offset, which is the
 * same record the app's shelf shows as "在读" — so the two clients agree on where you stopped.
 */

const READER_PREFS_KEY = 'ebookd.reader.prefs';
const READER_FONT_MIN = 16;
const READER_FONT_MAX = 26;
const READER_THEMES = ['paper', 'sepia', 'night'];
const READER_THEME_LABELS = { paper: '纸白', sepia: '米黄', night: '夜间' };

const reader = {
  book: null,
  file: null,
  /** 'txt' reads the bytes here; 'epub' asks the server for chapters. */
  kind: 'txt',
  text: '',
  /** Total readable characters — `text.length` for TXT, the sum of chapter lengths for EPUB. */
  totalChars: 0,
  chapters: [],
  index: 0,
  fontSize: 19,
  theme: 'paper',
  busy: false,
  saveTimer: null,
};

function loadReaderPrefs() {
  try {
    const saved = JSON.parse(localStorage.getItem(READER_PREFS_KEY) ?? '{}');
    if (Number.isFinite(saved.fontSize)) {
      reader.fontSize = Math.min(READER_FONT_MAX, Math.max(READER_FONT_MIN, saved.fontSize));
    }
    if (READER_THEMES.includes(saved.theme)) reader.theme = saved.theme;
  } catch {
    // A corrupt preference is not worth a broken reader; the defaults are fine.
  }
}

function saveReaderPrefs() {
  localStorage.setItem(READER_PREFS_KEY, JSON.stringify({ fontSize: reader.fontSize, theme: reader.theme }));
}

function applyReaderPrefs() {
  const node = $('#reader');
  node.dataset.theme = reader.theme;
  node.style.setProperty('--reader-font', `${reader.fontSize}px`);
  $('#reader-theme').textContent = READER_THEME_LABELS[reader.theme];
}

/**
 * What the reader can open. Plain text is read here; an EPUB is cut into chapters by the server,
 * because unzipping a container in the page would mean shipping a zip reader to it.
 */
function pickReaderSource(files) {
  const txt =
    files.find((f) => f.is_primary && f.format === 'txt' && f.status === 'ready') ??
    files.find((f) => f.format === 'txt' && f.status === 'ready');
  if (txt) return { kind: 'txt', file: txt };

  const epub =
    files.find((f) => f.is_primary && f.format === 'epub' && f.status === 'ready') ??
    files.find((f) => f.format === 'epub' && f.status === 'ready');
  if (epub) return { kind: 'epub', file: epub };

  return null;
}

/** Chapters of an EPUB, already split and sanitised server-side. */
async function fetchEpubContent(bookId, retried = false) {
  const res = await fetch(`/api/v1/books/${bookId}/epub`, {
    headers: state.access ? { authorization: `Bearer ${state.access}` } : {},
  });
  if (res.status === 401 && !retried && state.refresh) {
    await refreshToken();
    return fetchEpubContent(bookId, true);
  }
  if (!res.ok) {
    // The server names the reason (encrypted, damaged, unreadable) — pass it through rather than
    // turning every failure into "打不开".
    const body = await res.json().catch(() => null);
    throw new Error(body?.error?.message ?? `无法解析 EPUB（HTTP ${res.status}）`);
  }
  return res.json();
}

async function fetchBookBytes(fileId, retried = false) {
  const res = await fetch(`/api/v1/files/${fileId}/stream`, {
    headers: state.access ? { authorization: `Bearer ${state.access}` } : {},
  });
  // The access token lives fifteen minutes; a long reading session will outlive it.
  if (res.status === 401 && !retried && state.refresh) {
    await refreshToken();
    return fetchBookBytes(fileId, true);
  }
  if (!res.ok) throw new Error(`下载失败（HTTP ${res.status}）`);
  return res.arrayBuffer();
}

function decodeBook(buffer, charset) {
  const bytes = new Uint8Array(buffer);
  let text;
  // Ingest records the charset it detected, but a file that arrived from elsewhere can carry a
  // stale one. UTF-8 is self-validating — if it decodes with no bad byte at all, it is UTF-8.
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    try {
      text = new TextDecoder(charset || 'utf-8').decode(bytes);
    } catch {
      text = new TextDecoder('utf-8').decode(bytes);
    }
  }
  return text.replace(/^\uFEFF/u, '').replace(/\r\n?/g, '\n');
}

// A heading is a whole line: 第12章 … / 序章 / 番外 … The line must end shortly after the marker,
// otherwise a sentence that merely starts with 第三章 would be taken for a chapter break.
const CHAPTER_LINE = /^[ \t\u3000]*(第[0-9０-９零〇一二三四五六七八九十百千万两]{1,9}[章节回卷篇部集][^\n]{0,40}|(?:序章|楔子|前言|引言|后记|尾声|终章|番外)[^\n]{0,30})$/gmu;

function splitChapters(text) {
  const chapters = [];
  CHAPTER_LINE.lastIndex = 0;
  let match;
  while ((match = CHAPTER_LINE.exec(text)) !== null) {
    chapters.push({ title: match[1].trim(), start: match.index });
  }

  // Fewer than three headings means the file is not chaptered, or uses a scheme this does not
  // know. Equal slices still give a usable table of contents and a progress number that moves.
  if (chapters.length < 3) {
    chapters.length = 0;
    const CHUNK = 15000;
    let start = 0;
    let n = 1;
    while (start < text.length) {
      chapters.push({ title: `第 ${n} 部分`, start });
      let end = Math.min(text.length, start + CHUNK);
      if (end < text.length) {
        const newline = text.indexOf('\n', end);
        if (newline !== -1 && newline - end < 2000) end = newline + 1;
      }
      start = end;
      n += 1;
    }
  }

  // Anything before the first heading is a chapter of its own, or it could never be reached.
  if (chapters.length > 0 && chapters[0].start > 0) {
    if (text.slice(0, chapters[0].start).trim().length > 0) {
      chapters.unshift({ title: '开头', start: 0 });
    } else {
      chapters[0].start = 0;
    }
  }

  return chapters;
}

function chapterEnd(index) {
  return reader.chapters[index + 1]?.start ?? reader.totalChars;
}

function chapterAt(offset) {
  if (!Number.isFinite(offset) || offset <= 0) return 0;
  let index = 0;
  for (let i = 0; i < reader.chapters.length; i += 1) {
    if (reader.chapters[i].start <= offset) index = i;
    else break;
  }
  return index;
}

/** Where the reader was last time: the server's character offset, else its percentage. */
function restoreOffset(detail) {
  const mine = detail.my ?? {};
  try {
    const position = typeof mine.position_json === 'string' ? JSON.parse(mine.position_json) : mine.position_json;
    if (position && position.kind === 'char') {
      const value = Number(position.value);
      if (Number.isFinite(value) && value > 0) return value;
    }
  } catch {
    // A position written by another client in a shape this one does not know is simply ignored.
  }
  if (typeof mine.progress_percent === 'number' && mine.progress_percent > 0 && reader.totalChars > 0) {
    return Math.round((mine.progress_percent / 100) * reader.totalChars);
  }
  return 0;
}

async function openReader(bookId) {
  if (reader.busy) return;
  reader.busy = true;

  $('#detail').hidden = true;
  $('#reader').hidden = false;
  $('#reader-toc').hidden = true;
  $('#reader-end').hidden = true;
  document.body.classList.add('reading');
  applyReaderPrefs();
  $('#reader-book').textContent = '载入中…';
  $('#reader-chapter').textContent = '';
  $('#reader-percent').textContent = '0%';
  $('#reader-bar').style.width = '0%';
  $('#reader-text').replaceChildren(el('p', { className: 'reader-hint' }, '正在读取…'));

  try {
    const detail = await api(`/api/v1/books/${bookId}`);
    const source = pickReaderSource(detail.files ?? []);
    if (!source) throw new Error('这本书没有可在线阅读的 TXT / EPUB 文件，请下载后用本地阅读器打开');

    reader.book = detail.book;
    reader.file = source.file;
    reader.kind = source.kind;
    $('#reader-book').textContent = detail.book.title;

    if (source.kind === 'epub') {
      $('#reader-chapter').textContent = `${(source.file.size_bytes / 1048576).toFixed(1)} MB · 正在解析 EPUB…`;
      const content = await fetchEpubContent(bookId);
      if (!Array.isArray(content.chapters) || content.chapters.length === 0) {
        throw new Error('这个 EPUB 没有可读的章节');
      }

      // `start` is the character count before the chapter, so every offset in this file — restore,
      // percent, save — works for an EPUB exactly as it does for a TXT.
      let start = 0;
      reader.chapters = content.chapters.map((chapter) => {
        const entry = { title: chapter.title, html: chapter.html, start };
        start += chapter.chars;
        return entry;
      });
      reader.text = '';
      reader.totalChars = content.totalChars || start;
    } else {
      $('#reader-chapter').textContent = `${source.file.charset ?? 'UTF-8'} · ${(source.file.size_bytes / 1048576).toFixed(1)} MB · 正在下载…`;

      const buffer = await fetchBookBytes(source.file.id);
      $('#reader-chapter').textContent = '正在解析…';
      // Let that line paint before the main thread goes away for a few hundred milliseconds.
      await new Promise((resolve) => setTimeout(resolve, 0));

      const text = decodeBook(buffer, source.file.charset);
      if (!text.trim()) throw new Error('这个文件没有正文');

      reader.text = text;
      reader.chapters = splitChapters(text);
      reader.totalChars = text.length;
    }

    const offset = restoreOffset(detail);
    const index = chapterAt(offset);
    const chapter = reader.chapters[index];
    const span = chapter ? chapterEnd(index) - chapter.start : 0;
    const within = chapter && span > 0 ? (offset - chapter.start) / span : 0;

    renderToc();
    renderChapter(index, within);
  } catch (err) {
    reader.book = null;
    reader.file = null;
    $('#reader-chapter').textContent = '';
    $('#reader-text').replaceChildren(el('p', { className: 'reader-hint error' }, err.message ?? '打不开这本书'));
  } finally {
    reader.busy = false;
  }
}

function closeReader() {
  if ($('#reader').hidden) return;
  void saveProgress();
  $('#reader').hidden = true;
  $('#reader-toc').hidden = true;
  document.body.classList.remove('reading');
  reader.book = null;
  reader.file = null;
  reader.kind = 'txt';
  reader.text = '';
  reader.totalChars = 0;
  reader.chapters = [];
  reader.index = 0;
}

function renderChapter(index, within = 0) {
  if (reader.chapters.length === 0) return;
  const target = Math.min(reader.chapters.length - 1, Math.max(0, index));
  const chapter = reader.chapters[target];

  const fragment = document.createDocumentFragment();
  fragment.append(el('h2', { className: 'reader-title' }, chapter.title));

  const body = el('div', { className: 'reader-body' });
  if (typeof chapter.html === 'string') {
    // Safe by construction: the server rebuilds every block from a whitelist of tags and escapes
    // all text, so this string holds nothing but the headings/paragraphs/lists it produced. Raw
    // markup from the file never reaches the page.
    body.innerHTML = chapter.html;
    if (body.childNodes.length === 0) {
      body.append(el('p', { className: 'reader-hint' }, '这一节没有正文（可能只有图片）'));
    }
  } else {
    for (const line of reader.text.slice(chapter.start, chapterEnd(target)).split('\n')) {
      const text = line.replace(/^[ \t\u3000]+/u, '').trimEnd();
      if (text.length > 0) body.append(el('p', {}, text));
    }
  }
  fragment.append(body);

  reader.index = target;
  $('#reader-text').replaceChildren(fragment);
  $('#reader-chapter').textContent = `${chapter.title} · ${target + 1}/${reader.chapters.length}`;
  $('#reader-prev').disabled = target === 0;
  $('#reader-next').disabled = target === reader.chapters.length - 1;

  const scroller = $('#reader-scroll');
  scroller.scrollTop = 0;
  if (within > 0) {
    const max = scroller.scrollHeight - scroller.clientHeight;
    scroller.scrollTop = Math.round(max * Math.min(1, Math.max(0, within)));
  }

  highlightToc();
  updateReaderPercent();
}

function currentOffset() {
  if (reader.chapters.length === 0) return 0;
  const chapter = reader.chapters[reader.index];
  const span = chapterEnd(reader.index) - chapter.start;
  const scroller = $('#reader-scroll');
  const max = scroller.scrollHeight - scroller.clientHeight;
  const ratio = max > 0 ? Math.min(1, Math.max(0, scroller.scrollTop / max)) : 0;
  return Math.round(chapter.start + span * ratio);
}

function updateReaderPercent() {
  if (reader.totalChars === 0) return;
  const percent = Math.min(100, (currentOffset() / reader.totalChars) * 100);
  $('#reader-percent').textContent = `${percent.toFixed(percent < 10 ? 1 : 0)}%`;
  $('#reader-bar').style.width = `${percent.toFixed(2)}%`;
}

let readerScrollQueued = false;
function onReaderScroll() {
  if (readerScrollQueued) return;
  readerScrollQueued = true;
  requestAnimationFrame(() => {
    readerScrollQueued = false;
    updateReaderPercent();
    scheduleProgressSave();
  });
}

function scheduleProgressSave() {
  clearTimeout(reader.saveTimer);
  reader.saveTimer = setTimeout(() => {
    void saveProgress();
  }, 1500);
}

async function saveProgress() {
  clearTimeout(reader.saveTimer);
  if (!reader.book || !reader.file || reader.totalChars === 0) return;
  const offset = currentOffset();
  const percent = Math.min(100, (offset / reader.totalChars) * 100);
  try {
    await api(`/api/v1/progress/${reader.book.id}`, {
      method: 'PUT',
      body: {
        fileId: reader.file.id,
        targetKind: 'char',
        targetValue: String(offset),
        percent: Number(percent.toFixed(2)),
        label: reader.chapters[reader.index]?.title ?? null,
      },
    });
  } catch {
    // Progress is best effort: a failed sync must not interrupt reading.
  }
}

function goToChapter(index) {
  if (index < 0 || index >= reader.chapters.length) return;
  renderChapter(index, 0);
  $('#reader-scroll').scrollTop = 0;
  scheduleProgressSave();
}

function stepChapter(delta) {
  const next = reader.index + delta;
  if (next < 0) {
    toast('已经是第一章');
    return;
  }
  if (next >= reader.chapters.length) {
    toast('已经是最后一章');
    return;
  }
  goToChapter(next);
}

function renderToc() {
  $('#reader-toc-list').replaceChildren(
    ...reader.chapters.map((chapter, index) =>
      el('li', {
        className: index === reader.index ? 'current' : '',
        onclick: () => {
          goToChapter(index);
          toggleToc(false);
        },
      },
        el('span', { className: 'toc-index' }, String(index + 1)),
        el('span', { className: 'toc-title' }, chapter.title),
      ),
    ),
  );
}

function highlightToc() {
  const items = $('#reader-toc-list').children;
  for (let i = 0; i < items.length; i += 1) {
    items[i].classList.toggle('current', i === reader.index);
  }
  if (!$('#reader-toc').hidden) {
    items[reader.index]?.scrollIntoView({ block: 'center' });
  }
}

function toggleToc(force) {
  const open = force ?? $('#reader-toc').hidden;
  $('#reader-toc').hidden = !open;
  if (open) highlightToc();
}

function setReaderFont(delta) {
  reader.fontSize = Math.min(READER_FONT_MAX, Math.max(READER_FONT_MIN, reader.fontSize + delta));
  saveReaderPrefs();
  applyReaderPrefs();
  // Keep the reader where it was: the same scroll offset means a different place once the lines
  // reflow, so anchor on the ratio instead.
  const scroller = $('#reader-scroll');
  const max = scroller.scrollHeight - scroller.clientHeight;
  const ratio = max > 0 ? scroller.scrollTop / max : 0;
  requestAnimationFrame(() => {
    const nextMax = scroller.scrollHeight - scroller.clientHeight;
    if (nextMax > 0) scroller.scrollTop = Math.round(nextMax * ratio);
  });
}

function cycleReaderTheme() {
  const index = READER_THEMES.indexOf(reader.theme);
  reader.theme = READER_THEMES[(index + 1) % READER_THEMES.length];
  saveReaderPrefs();
  applyReaderPrefs();
}

// ---------------------------------------------------------------- detail

async function openDetail(bookId) {
  const detail = await api(`/api/v1/books/${bookId}`);
  const book = detail.book;
  const body = $('#detail-body');
  body.replaceChildren(
    el('h1', {}, book.title),
    el('p', { className: 'muted' }, `格式：${detail.files.map((f) => f.format).join('、')}　共 ${detail.files.length} 个文件`),
    el('div', { className: 'row' },
      el('button', {
        className: 'primary',
        onclick: () => void openReader(book.id),
      }, detail.my?.status === 'reading' ? '继续阅读' : '开始阅读'),
      el('span', { className: 'muted' },
        detail.my?.progress_percent ? `已读 ${Number(detail.my.progress_percent).toFixed(1)}%` : '还没读过',
      ),
    ),
    el('div', { className: 'card' },
      el('label', {}, '书名', el('input', { id: 'edit-title', value: book.title })),
      el('label', {}, '作者（用 / 分隔）', el('input', { id: 'edit-authors', value: (book.authors ?? []).join(' / ') })),
      el('label', {}, '出版年', el('input', { id: 'edit-year', type: 'number', value: book.published_year ?? '' })),
      el('label', {}, '语言', el('input', { id: 'edit-language', value: book.language ?? '' })),
      el('label', {}, '出版社', el('input', { id: 'edit-publisher', value: book.publisher ?? '' })),
      el('label', {}, '简介', el('textarea', { id: 'edit-description', rows: 5 }, book.description ?? '')),
      el('label', {}, '标签（逗号分隔）', el('input', { id: 'edit-tags', value: (book.tags ?? []).join(', ') })),
      el('div', { className: 'row' },
        el('button', { onclick: () => saveBook(book.id) }, '保存'),
        el('label', { className: 'check' },
          el('input', { id: 'edit-clear-review', type: 'checkbox', checked: book.needs_review === 0 }),
          '已确认',
        ),
      ),
    ),
    el('div', { className: 'card' },
      el('h1', {}, '文件'),
      el('ul', { className: 'tree' },
        ...detail.files.map((f) =>
          el('li', {},
            el('span', {}, `${f.format} · ${(f.size_bytes / 1024 / 1024).toFixed(1)} MB`),
            el('span', { className: 'count' }, f.filename),
            el('a', { href: `/api/v1/files/${f.id}/download`, className: 'muted' }, '下载'),
          ),
        ),
      ),
    ),
  );
  $('#detail').hidden = false;
}

async function saveBook(bookId) {
  const title = $('#edit-title').value.trim();
  try {
    await api(`/api/v1/books/${bookId}`, {
      method: 'PATCH',
      body: {
        title,
        authors: $('#edit-authors').value.split('/').map((s) => s.trim()).filter(Boolean),
        publisher: $('#edit-publisher').value.trim() || null,
        publishedYear: $('#edit-year').value ? Number($('#edit-year').value) : null,
        language: $('#edit-language').value.trim() || null,
        description: $('#edit-description').value || null,
        tags: $('#edit-tags').value.split(',').map((s) => s.trim()).filter(Boolean),
        clearNeedsReview: $('#edit-clear-review').checked,
      },
    });
    toast('已保存；这些字段会被标记为人工修改，重新解析不会覆盖');
    $('#detail').hidden = true;
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

// ---------------------------------------------------------------- taxonomy

async function refreshTaxonomy() {
  const [categories, collections, tags] = await Promise.all([
    api('/api/v1/categories'),
    api('/api/v1/collections'),
    api('/api/v1/tags'),
  ]);
  state.categories = categories.items;
  state.collections = collections.items;
  state.tags = tags.items;
  // The parent picker only offers roots: a sub-category cannot own another one, so listing the
  // rest would just produce a 422 after the fact. The two filter pickers still list everything.
  const parentNode = $('#category-parent');
  const keepParent = parentNode.value;
  parentNode.replaceChildren(
    el('option', { value: '' }, '作为根分类'),
    ...state.categories
      .filter((c) => !c.parent_id)
      .map((c) => el('option', { value: c.id }, `挂在「${c.name}」下`)),
  );
  parentNode.value = keepParent;

  for (const select of ['#filter-category', '#batch-category']) {
    const node = $(select);
    const keep = node.value;
    const options = state.categories.map((c) => el('option', { value: c.id }, `${'　'.repeat((c.path.match(/\//g) ?? []).length - 1)}${c.name}`));
    node.replaceChildren(el('option', { value: '' }, select === '#filter-category' ? '全部分类' : '选择分类…'), ...options);
    node.value = keep;
  }
  for (const select of ['#filter-collection', '#batch-collection']) {
    const node = $(select);
    const keep = node.value;
    node.replaceChildren(
      el('option', { value: '' }, select === '#filter-collection' ? '全部分组' : '选择分组…'),
      ...state.collections.map((c) => el('option', { value: c.id }, `${c.name}（${c.book_count ?? 0}）${c.owner_user_id ? '' : ' · 共享'}`)),
    );
    node.value = keep;
  }
}

function renderTaxonomy() {
  renderCategoryTree();

  $('#collection-list').replaceChildren(
    ...state.collections.map((c) =>
      el('li', {},
        el('span', {}, c.name),
        c.owner_user_id ? null : el('span', { className: 'badge' }, '共享'),
        el('span', { className: 'count' }, `${c.book_count ?? 0} 本`),
        el('span', { className: 'spacer' }),
        el('button', {
          className: 'ghost danger',
          onclick: async () => {
            if (!(await askConfirm({ title: `删除分组「${c.name}」`, text: '书籍本身不会被删除。' }))) return;
            try {
              await api(`/api/v1/collections/${c.id}`, { method: 'DELETE' });
              await refreshTaxonomy();
              renderTaxonomy();
            } catch (err) {
              toast(err.message, true);
            }
          },
        }, '删除'),
      ),
    ),
  );

  $('#tag-list').replaceChildren(
    ...state.tags.map((tag) =>
      el('li', {},
        el('span', {}, tag.name),
        el('span', { className: 'count' }, `${tag.usage_count} 本`),
        el('span', { className: 'spacer' }),
        el('button', {
          className: 'ghost',
          onclick: async () => {
            const target = await askText({
              title: `把「${tag.name}」合并到哪个标签`,
              text: '输入目标标签名；不存在会新建。',
              okText: '合并',
            });
            if (!target) return;
            const existing = state.tags.find((t) => t.name === target);
            const targetId = existing
              ? existing.id
              : (await api('/api/v1/tags', { method: 'POST', body: { name: target } })).tag.id;
            try {
              const result = await api('/api/v1/tags/merge', { method: 'POST', body: { sourceIds: [tag.id], targetId } });
              toast(`已合并 ${result.books} 条书籍标签`);
              await refreshTaxonomy();
              renderTaxonomy();
            } catch (err) {
              toast(err.message, true);
            }
          },
        }, '合并'),
        el('button', {
          className: 'ghost danger',
          onclick: async () => {
            if (!(await askConfirm({ title: `删除标签「${tag.name}」`, text: '标签会从所有书上移除，书本身不动。' }))) return;
            await api(`/api/v1/tags/${tag.id}`, { method: 'DELETE' });
            await refreshTaxonomy();
            renderTaxonomy();
          },
        }, '删除'),
      ),
    ),
  );

  // The shelf tabs and the breadcrumb read the same category list.
  renderShelfTabs();
  renderShelfCrumbs();
}

/**
 * Two levels drawn as two levels: a root row, and its sub-categories indented under it.
 *
 * The root's number is the whole shelf — its own books plus everything filed one level down —
 * because that is how the app counts a shelf. `book_count` from the API is per category, so the
 * total is summed here instead of asked for.
 */
function renderCategoryTree() {
  const childrenOf = new Map();
  for (const c of state.categories) {
    if (!c.parent_id) continue;
    if (!childrenOf.has(c.parent_id)) childrenOf.set(c.parent_id, []);
    childrenOf.get(c.parent_id).push(c);
  }

  const roots = state.categories
    .filter((c) => !c.parent_id)
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));

  const rows = [];
  roots.forEach((root, rootIndex) => {
    const children = (childrenOf.get(root.id) ?? []).sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
    const own = root.book_count ?? 0;
    const total = own + children.reduce((sum, c) => sum + (c.book_count ?? 0), 0);

    rows.push(categoryRow(root, {
      count: children.length ? `本架 ${own} · 含子分类 ${total} 本` : `${own} 本`,
      index: rootIndex,
      levelSize: roots.length,
    }));
    children.forEach((child, childIndex) => {
      rows.push(categoryRow(child, {
        child: true,
        count: `${child.book_count ?? 0} 本`,
        index: childIndex,
        levelSize: children.length,
      }));
    });
  });

  $('#category-list').replaceChildren(...rows);
}

function categoryRow(category, options = {}) {
  const atStart = (options.index ?? 0) === 0;
  const atEnd = (options.index ?? 0) === (options.levelSize ?? 1) - 1;

  return el('li', { className: options.child ? 'child' : 'root' },
    el('span', { className: 'name' }, category.name),
    el('span', { className: 'count' }, options.count),
    el('span', { className: 'spacer' }),
    // Ordering lives here rather than on the tabs: a tab row only fits left/right dragging, and a
    // drag is exactly the gesture that fights a horizontally scrolling strip.
    el('span', { className: 'move' },
      el('button', {
        className: 'ghost',
        title: '上移',
        disabled: atStart,
        onclick: () => moveCategory(category, -1),
      }, '↑'),
      el('button', {
        className: 'ghost',
        title: '下移',
        disabled: atEnd,
        onclick: () => moveCategory(category, 1),
      }, '↓'),
    ),
    options.child ? null : el('button', { className: 'ghost', onclick: () => addSubCategory(category) }, '＋ 子分类'),
    el('button', { className: 'ghost', onclick: () => renameCategory(category) }, '改名'),
    el('button', { className: 'ghost danger', onclick: () => deleteCategory(category) }, '删除'),
  );
}

/**
 * Moves one shelf within its own level.
 *
 * The API takes the whole level, so this swaps two ids and sends everything: the server then
 * rewrites dense positions in one transaction, and the tab row — which reads the same order — moves
 * with it.
 */
async function moveCategory(category, delta) {
  const parentId = category.parent_id ?? null;
  const siblings = state.categories
    .filter((c) => (c.parent_id ?? null) === parentId)
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));

  const index = siblings.findIndex((c) => c.id === category.id);
  const target = index + delta;
  if (index < 0 || target < 0 || target >= siblings.length) return;

  const ids = siblings.map((c) => c.id);
  [ids[index], ids[target]] = [ids[target], ids[index]];

  try {
    await api('/api/v1/categories/reorder', { method: 'POST', body: { parentId, ids } });
    await refreshTaxonomy();
    renderTaxonomy();
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

async function addSubCategory(parent) {
  const name = await askText({
    title: `在「${parent.name}」下新建子分类`,
    value: '',
    okText: '创建',
  });
  if (!name) return;
  try {
    await api('/api/v1/categories', { method: 'POST', body: { name, parentId: parent.id } });
    await refreshTaxonomy();
    renderTaxonomy();
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

async function renameCategory(category) {
  const name = await askText({ title: '重命名分类', value: category.name, okText: '保存' });
  if (!name || name === category.name) return;
  try {
    await api(`/api/v1/categories/${category.id}`, { method: 'PATCH', body: { name } });
    await refreshTaxonomy();
    renderTaxonomy();
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

async function deleteCategory(category) {
  const children = state.categories.filter((c) => c.parent_id === category.id).length;
  const confirmed = await askConfirm({
    title: `删除分类「${category.name}」`,
    text: children
      ? `它的 ${children} 个子分类会上移到根级，书籍会变为未分类。`
      : '书籍会变为未分类。',
  });
  if (!confirmed) return;
  try {
    await api(`/api/v1/categories/${category.id}`, { method: 'DELETE' });
    // If the shelf being looked at is the one that just went away, fall back to its parent instead
    // of leaving the grid filtered by an id that no longer exists.
    if ($('#filter-category').value === category.id) {
      $('#filter-category').value = category.parent_id ?? '';
    }
    await refreshTaxonomy();
    renderTaxonomy();
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

/**
 * Removes one book, from the chip on its cover.
 *
 * The same soft delete the batch bar performs — tombstone now, bytes after the retention window —
 * routed through the single-book endpoint so the commonest edit (take one title off the shelf)
 * does not need a selection first. The confirm is not optional: the chip sits a thumb's width from
 * "阅读", and a delete that fires on a mis-aimed click is the one mistake this console cannot
 * undo for the reader.
 */
async function deleteBook(book) {
  const confirmed = await askConfirm({
    title: `删除《${book.title}》`,
    text: '文件会在保留期内继续可下载，之后才被回收。同步过的设备会在下次同步时移除它。',
  });
  if (!confirmed) return;
  try {
    await api(`/api/v1/books/${book.id}`, { method: 'DELETE' });
    toast(`已删除《${book.title}》`);
    // The shelf the book was in may now be empty, and a folder tile's count is stale either way.
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

// ---------------------------------------------------------------- upload

/** Serialises the name-clash questions; see the conflict branch of sendUpload. */
let clashQueue = Promise.resolve();

function uploadOne(file) {
  const row = el('div', { className: 'upload-row' },
    el('span', {}, file.name),
    el('div', { className: 'progress' }, el('i', {})),
    el('span', { className: 'muted' }, '等待'),
  );
  $('#upload-list').append(row);
  sendUpload(file, row, null);
}

/**
 * One attempt at putting `file` on the server.
 *
 * `conflict` is null on the first try and the operator's decision on the retry after a
 * `filename_conflict`. The File object is still in memory in the browser, so answering the
 * question costs nothing but the bytes' second trip — which is the one honest cost of asking
 * after the upload instead of parking the temp file server-side.
 */
function sendUpload(file, row, conflict) {
  const bar = row.querySelector('i');
  const status = row.querySelector('span:last-child');

  // XHR rather than fetch: fetch still cannot report upload progress, and a 900 MB comic with no
  // progress indicator reads as a hung browser.
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/v1/uploads');
  xhr.setRequestHeader('authorization', `Bearer ${state.access}`);
  xhr.upload.onprogress = (event) => {
    if (!event.lengthComputable) return;
    bar.style.width = `${Math.round((event.loaded / event.total) * 100)}%`;
    status.textContent = `${Math.round(event.loaded / 1024 / 1024)} / ${Math.round(event.total / 1024 / 1024)} MB`;
  };
  xhr.onload = () => {
    const body = (() => {
      try {
        return JSON.parse(xhr.responseText);
      } catch {
        return null;
      }
    })();

    if (xhr.status === 409 && body?.error?.code === 'filename_conflict') {
      // The shelf already holds this name. Nothing was stored; ask which file wins and send the
      // same bytes again with the answer.
      //
      // One question at a time: the modal has a single resolver slot, and a multi-file selection
      // can clash on several names at once. Queueing keeps the second row waiting instead of
      // orphaning the first dialog's promise, which would leave that row stuck at 等待处理 forever.
      row.classList.remove('bad');
      status.textContent = '等待处理…';
      clashQueue = clashQueue.then(async () => {
        const decision = await askNameClash(file, body.error.details ?? {});
        if (!decision) {
          row.classList.add('bad');
          status.textContent = '已取消';
          return;
        }
        status.textContent = '按选择重新上传…';
        sendUpload(file, row, decision);
      });
      return;
    }

    if (xhr.status === 201) {
      row.classList.add('ok');
      // The three clash answers each deserve their own sentence: "已挂到现有书籍" is true of all
      // of them and says nothing about which file survived.
      if (body.conflict === 'replace') {
        status.textContent = `已覆盖：《${body.title}》的文件已换成新内容`;
      } else if (body.conflict === 'rename') {
        status.textContent = `两者都留：新文件「${body.storedFilename}」已存入《${body.title}》`;
      } else {
        status.textContent = body.created ? `已入库：${body.title}` : `已挂到现有书籍：${body.title}`;
      }
      if (body.book?.needsReview) status.textContent += '（标题待确认）';
      loadShelf(true).catch(() => undefined);
    } else if (xhr.status === 200 && body?.skipped) {
      row.classList.add('ok');
      status.textContent = '已跳过，书库保持原样';
    } else {
      row.classList.add('bad');
      status.textContent = body?.error?.message ?? `失败 (${xhr.status})`;
    }
  };
  xhr.onerror = () => {
    row.classList.add('bad');
    status.textContent = '网络错误';
  };
  // A FormData wrapper is required, not optional: sending a File directly makes the browser set
  // Content-Type to the file's own type (text/plain), so the request arrives as a plain body and
  // the server correctly refuses it as not multipart. The boundary has to come from the browser,
  // which is why no Content-Type is set here.
  const form = new FormData();
  form.append('file', file);
  if (conflict) form.append('conflict', conflict);
  xhr.send(form);
}

/** The three answers to "this name is already taken", in the order of increasing loss. */
async function askNameClash(file, details) {
  const size = details.sizeBytes ? `（${(details.sizeBytes / 1024 / 1024).toFixed(1)} MB）` : '';
  return askChoice({
    title: `同名文件「${file.name}」`,
    text: `书库中《${details.bookTitle ?? '未知书籍'}》已持有文件「${details.filename ?? file.name}」${size}。本次上传的文件名与之相同、内容不同。`,
    choices: [
      { id: 'replace', label: '覆盖原文件 —— 新内容替换旧文件，书架与阅读进度保留' },
      { id: 'rename', label: '两者都留 —— 新文件加序号存入同一本书，并设为主文件' },
      { id: 'skip', label: '跳过 —— 不上传，书库保持原样' },
    ],
  });
}

// ---------------------------------------------------------------- users & status

async function loadUsers() {
  const list = await api('/api/v1/users?limit=200');
  const body = $('#user-table tbody');
  body.replaceChildren(
    ...list.items.map((user) =>
      el('tr', {},
        el('td', {}, user.username),
        el('td', {}, user.role),
        el('td', {}, user.status),
        el('td', {},
          el('button', {
            className: 'ghost',
            onclick: async () => {
              const password = await askText({
                title: `给「${user.username}」设置新密码`,
                text: '至少 10 位；该用户其它设备会被登出。',
                secret: true,
                okText: '重置',
              });
              if (!password) return;
              try {
                const result = await api(`/api/v1/users/${user.id}/password`, { method: 'POST', body: { newPassword: password } });
                toast(`已重置，${result.sessionsRevoked} 个会话被撤销`);
              } catch (err) {
                toast(err.message, true);
              }
            },
          }, '重置密码'),
          el('button', { className: 'ghost', onclick: () => toggleUser(user) }, user.status === 'active' ? '停用' : '启用'),
        ),
      ),
    ),
  );
}

async function toggleUser(user) {
  try {
    await api(`/api/v1/users/${user.id}`, { method: 'PATCH', body: { status: user.status === 'active' ? 'disabled' : 'active' } });
    await loadUsers();
  } catch (err) {
    toast(err.message, true);
  }
}

async function loadStatus() {
  const [ready, stats] = await Promise.all([api('/ready'), api('/api/v1/stats').catch(() => null)]);
  $('#status-dl').replaceChildren(
    el('dt', {}, '版本'), el('dd', {}, ready.version),
    el('dt', {}, '实例'), el('dd', {}, ready.instanceId),
    el('dt', {}, '数据库版本'), el('dd', {}, String(ready.schemaVersion)),
    el('dt', {}, '运行时长'), el('dd', {}, `${ready.uptimeSeconds ?? '-'} 秒`),
    ...(stats
      ? [
          el('dt', {}, '书籍'), el('dd', {}, String(stats.books ?? '-')),
          el('dt', {}, '文件'), el('dd', {}, String(stats.files ?? '-')),
          el('dt', {}, '占用'), el('dd', {}, `${((stats.bytes ?? 0) / 1024 / 1024).toFixed(1)} MB`),
        ]
      : []),
  );
  const audit = await api('/api/v1/audit-logs?limit=50').catch(() => null);
  $('#audit').textContent = audit ? JSON.stringify(audit.items, null, 1) : '（需要管理员权限）';
}

// ---------------------------------------------------------------- wiring

function wire() {
  $('#login-form').onsubmit = async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    $('#login-error').textContent = '';
    try {
      const res = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          username: form.get('username'),
          password: form.get('password'),
          device: { label: 'Web 管理页', platform: 'web' },
        }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body?.error?.message ?? '登录失败');
      setTokens(body.accessToken, body.refreshToken);
      await showApp();
    } catch (err) {
      $('#login-error').textContent = err.message;
    }
  };

  $('#logout').onclick = async () => {
    try {
      await api('/api/v1/auth/logout', { method: 'POST', body: { refreshToken: state.refresh } });
    } catch {
      /* signing out locally is enough */
    }
    clearTokens();
    // Object URLs are pinned to the document until revoked, so signing out without this leaks one
    // blob per cover that was ever displayed.
    releaseCovers();
    showLogin();
  };

  for (const tab of document.querySelectorAll('.tab')) {
    tab.onclick = () => switchView(tab.dataset.view).catch((err) => toast(err.message, true));
  }

  let searchTimer = null;
  $('#search').oninput = () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => loadShelf(true).catch((err) => toast(err.message, true)), 250);
  };
  for (const node of ['#sort', '#filter-category', '#filter-collection', '#filter-review']) {
    $(node).onchange = () => loadShelf(true).catch((err) => toast(err.message, true));
  }
  $('#more').onclick = () => loadShelf(false).catch((err) => toast(err.message, true));

  $('#batch-toggle').onclick = () => {
    state.batching = !state.batching;
    state.selected.clear();
    document.body.classList.toggle('batching', state.batching);
    $('#batch-bar').hidden = !state.batching;
    $('#batch-toggle').textContent = state.batching ? '结束批量' : '批量';
    renderShelf();
    updateBatchBar();
  };
  $('#batch-clear').onclick = () => {
    state.selected.clear();
    renderShelf();
    updateBatchBar();
  };
  for (const button of document.querySelectorAll('[data-batch]')) {
    button.onclick = () => runBatch(button.dataset.batch);
  }

  $('#upload-file').onchange = (event) => {
    for (const file of event.target.files) uploadOne(file);
    event.target.value = '';
  };

  $('#import-preview').onclick = () => runImport(true);
  $('#import-run').onclick = () => runImport(false);

  $('#sub-shelf-close').onclick = closeSubShelfSheet;
  $('#sub-shelf .scrim').onclick = closeSubShelfSheet;
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('#sub-shelf').hidden && $('#modal').hidden) {
      closeSubShelfSheet();
    }
  });

  $('#modal-cancel').onclick = () => closeModal(false);
  $('#modal-ok').onclick = () => closeModal(true);
  $('#modal').onclick = (event) => {
    if (event.target.id === 'modal') closeModal(false);
  };
  document.addEventListener('keydown', (event) => {
    if ($('#modal').hidden) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      closeModal(false);
    } else if (event.key === 'Enter' && $('#modal-choices').hidden && !$('#modal-ok').disabled) {
      event.preventDefault();
      closeModal(true);
    }
  });

  wireShelfTabDrag();

  loadReaderPrefs();
  applyReaderPrefs();
  $('#reader-back').onclick = closeReader;
  $('#reader-toc-toggle').onclick = () => toggleToc();
  $('#reader-toc-close').onclick = () => toggleToc(false);
  $('#reader-font-down').onclick = () => setReaderFont(-1);
  $('#reader-font-up').onclick = () => setReaderFont(1);
  $('#reader-theme').onclick = cycleReaderTheme;
  $('#reader-prev').onclick = () => stepChapter(-1);
  $('#reader-next').onclick = () => stepChapter(1);
  $('#reader-scroll').addEventListener('scroll', onReaderScroll, { passive: true });
  // Leaving the tab mid-chapter should still record where the reader was.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && !$('#reader').hidden) void saveProgress();
  });
  document.addEventListener('keydown', (event) => {
    if ($('#reader').hidden || !$('#modal').hidden) return;
    if (event.target.closest('input, textarea')) return;
    // Escape backs out one layer at a time: the table of contents first, the reader second.
    if (event.key === 'Escape') {
      if (!$('#reader-toc').hidden) toggleToc(false);
      else closeReader();
    }
    else if (event.key === 'ArrowLeft') stepChapter(-1);
    else if (event.key === 'ArrowRight') stepChapter(1);
    else if (event.key === 't' || event.key === 'T') toggleToc();
    else if (event.key === 'PageDown' || event.key === ' ') {
      $('#reader-scroll').scrollBy({ top: $('#reader-scroll').clientHeight * 0.9, behavior: 'smooth' });
    } else if (event.key === 'PageUp') {
      $('#reader-scroll').scrollBy({ top: -$('#reader-scroll').clientHeight * 0.9, behavior: 'smooth' });
    } else {
      return;
    }
    event.preventDefault();
  });

  $('#create-toggle').onclick = (event) => {
    event.stopPropagation();
    const menu = $('#create-menu');
    menu.hidden = !menu.hidden;
  };
  $('#create-shelf').onclick = () => {
    $('#create-menu').hidden = true;
    createCategoryQuick(null);
  };
  $('#create-sub-shelf').onclick = () => {
    $('#create-menu').hidden = true;
    const { root } = currentShelf();
    if (root) createCategoryQuick(root);
  };
  // Any click that is not on the menu closes it — the usual dropdown contract.
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.shelf-create')) $('#create-menu').hidden = true;
  });

  $('#category-add').onclick = async () => {
    const name = $('#category-name').value.trim();
    if (!name) return;
    try {
      await api('/api/v1/categories', { method: 'POST', body: { name, parentId: $('#category-parent').value || null } });
      $('#category-name').value = '';
      await refreshTaxonomy();
      renderTaxonomy();
    } catch (err) {
      toast(err.message, true);
    }
  };

  $('#collection-add').onclick = async () => {
    const name = $('#collection-name').value.trim();
    if (!name) return;
    try {
      await api('/api/v1/collections', {
        method: 'POST',
        body: { name, shared: $('#collection-shared').checked },
      });
      $('#collection-name').value = '';
      await refreshTaxonomy();
      renderTaxonomy();
    } catch (err) {
      toast(err.message, true);
    }
  };

  $('#tag-add').onclick = async () => {
    const name = $('#tag-name').value.trim();
    if (!name) return;
    try {
      await api('/api/v1/tags', { method: 'POST', body: { name } });
      $('#tag-name').value = '';
      await refreshTaxonomy();
      renderTaxonomy();
    } catch (err) {
      toast(err.message, true);
    }
  };

  $('#user-add').onclick = async () => {
    try {
      const created = await api('/api/v1/users', {
        method: 'POST',
        body: {
          username: $('#user-name').value.trim(),
          displayName: $('#user-display').value.trim() || $('#user-name').value.trim(),
          password: $('#user-password').value,
          role: $('#user-role').value,
        },
      });
      // Shown once, on purpose: there is no mail transport in this build, so this is the only
      // moment the initial password exists anywhere the operator can read it.
      alert(`已创建 ${created.user.username}\n初始密码（只显示这一次）：${created.initialPassword}`);
      $('#user-name').value = '';
      $('#user-display').value = '';
      $('#user-password').value = '';
      await loadUsers();
    } catch (err) {
      toast(err.message, true);
    }
  };

  $('#detail-close').onclick = () => {
    $('#detail').hidden = true;
  };
}

async function runImport(dryRun) {
  const path = $('#import-path').value.trim();
  if (!path) return toast('填写要导入的目录', true);
  // Linking leaves the files where they are; the message has to say so, or "0 个失败" reads like
  // the files were taken away.
  const link = $('#import-link').checked;
  try {
    const result = await api('/api/v1/import/dir', { method: 'POST', body: { path, dryRun, link } });
    const node = $('#import-result');
    node.hidden = false;
    node.textContent = dryRun
      ? `将导入 ${result.scanned} 个文件${link ? '（原地引用，文件不动）' : ''}`
      : `扫描 ${result.scanned} 个，成功导入 ${result.imported} 个，失败 ${result.failed?.length ?? 0} 个${
          link ? '（原地引用，源文件保留）' : ''
        }`;
    await loadShelf(true);
  } catch (err) {
    toast(err.message, true);
  }
}

wire();
wireAccount();
if (state.access) showApp().catch(showLogin);
else showLogin();
