const app = document.querySelector('#app');
const nav = [...document.querySelectorAll('nav [data-target]')];
const searchInput = document.querySelector('#catalog-search');
const clearSearch = document.querySelector('#clear-search');

const movies = ['Night Drive', 'Zero Hour', 'The Archive', 'New Release', 'Northline', 'Signal Run', 'Afterlight', 'Red Meridian', 'The Long Way', 'Paper City', 'Glass Harbour', 'Final Frequency'];
const movieCategories = [
  { title: 'Arabic Cinema', items: ['The Archive', 'Red Meridian', 'The Long Way', 'Afterlight', 'Cairo After Dark', 'Desert Letters', 'The Last Minaret', 'Nile Avenue', 'Half a Story', 'Summer in Alexandria'] },
  { title: 'Action & Adventure', items: ['Night Drive', 'Zero Hour', 'Signal Run', 'Northline', 'Final Frequency', 'Rogue Signal', 'Black Coast', 'The Long Pursuit', 'Fireline', 'Northern Passage'] },
  { title: 'Drama', items: ['Paper City', 'Glass Harbour', 'The Archive', 'New Release', 'The Long Way', 'A Quiet Exit', 'Second Harbour', 'The Last Letter', 'River of Light', 'Winter Garden'] },
  { title: 'Comedy', items: ['Late Checkout', 'Side Street', 'The Good Neighbours', 'Small Talk', 'Family Table', 'Opening Night', 'The Wrong Train', 'Weekend Shift', 'Blue Taxi', 'Almost Famous'] },
  { title: 'Crime & Mystery', items: ['Zero Hour', 'Red Meridian', 'Night Drive', 'The Archive', 'The Silent File', 'Cold Signal', 'Missing Frame', 'Harbour Case', 'False Start', 'The Witness Room'] },
  { title: 'Science Fiction', items: ['Afterlight', 'Signal Run', 'Final Frequency', 'Orbit Fall', 'Static Moon', 'Last Transmission', 'The Far Shore', 'Solar Drift', 'Ghost Satellite', 'Tomorrow Station'] },
  { title: 'Family', items: ['New Release', 'Northline', 'Paper City', 'The Long Way', 'Bright Sunday', 'The Tree House', 'First Snow', 'Little Comet', 'Summer Camp', 'Home Again'] },
  { title: 'Documentary', items: ['Nile Avenue', 'Desert Letters', 'River of Light', 'The Last Minaret', 'Wild Horizon', 'Field Notes', 'True North', 'City Makers', 'Open Water', 'The Long Road'] },
];
const movieShelves = [
  { title: 'Continue watching', items: ['Night Drive', 'The Archive', 'Northline'] },
  { title: 'Recently added', items: movies.slice(0, 8) },
  { title: 'Favorites', items: ['The Long Way', 'Afterlight', 'Red Meridian', 'Glass Harbour'] },
  ...movieCategories,
];
const series = ['Signal House', 'Afterlight', 'Blue Static', 'Orbit City', 'Quiet Sea', 'Northline', 'The Crossing', 'Glass District', 'Cairo Files', 'Open Season', 'The Last Line', 'Border Town'];
const seriesCategories = [
  { title: 'Arabic Drama', items: ['Quiet Sea', 'The Crossing', 'Glass District', 'Signal House', 'Cairo Files', 'Border Town', 'The Long Road', 'House of Jasmine', 'Between Two Rivers', 'The Orchard'] },
  { title: 'Crime Thrillers', items: ['Signal House', 'Blue Static', 'Northline', 'Orbit City', 'The Last Line', 'Open Season', 'The Broken Room', 'South District', 'Paper Evidence', 'Cold Case 9'] },
  { title: 'Sci-Fi & Fantasy', items: ['Orbit City', 'Afterlight', 'Blue Static', 'The Crossing', 'Signal House', 'Neon Atlas', 'The Other Sky', 'Moon Archive', 'Future Tense', 'Far Horizon'] },
  { title: 'Comedy', items: ['Open Season', 'Border Town', 'The Neighbours', 'Late Shift', 'Family Business', 'The Flat Upstairs', 'Second Chances', 'Three of Us', 'Long Weekend', 'Good Company'] },
  { title: 'Historical', items: ['Cairo Files', 'The Last Line', 'Quiet Sea', 'The Crossing', 'Empire Road', 'The Silk Route', 'House of Stone', 'The Crownmaker', 'Old Harbour', 'The Last Kingdom'] },
  { title: 'Romance', items: ['Glass District', 'Afterlight', 'Quiet Sea', 'The Crossing', 'The Long Summer', 'A Second Look', 'Letters Home', 'Near Enough', 'The Garden Wall', 'Before Morning'] },
  { title: 'Documentary Series', items: ['Open Season', 'Cairo Files', 'Signal House', 'Northline', 'Hidden Cities', 'Planet North', 'True Stories', 'The Workshop', 'Inside the Line', 'Wild Seasons'] },
  { title: 'Kids & Family', items: ['Orbit City', 'Border Town', 'The Crossing', 'Blue Static', 'Little Explorers', 'Treehouse Tales', 'Junior Detectives', 'Campfire Club', 'The Bright Bunch', 'Small Wonders'] },
];
const seriesShelves = [
  { title: 'Continue watching', items: ['Signal House', 'Quiet Sea', 'The Crossing'] },
  { title: 'Recently added', items: series.slice(0, 8) },
  { title: 'Favorites', items: ['Signal House', 'Quiet Sea', 'Cairo Files', 'The Last Line'] },
  ...seriesCategories,
];
const channelsByGroup = {
  News: ['News 24', 'World Documentary', 'Culture East', 'Global Report', 'Cairo News', 'France 24', 'Al Jazeera', 'BBC World', 'Sky News', 'Euronews'],
  Sports: ['Sports Arena', 'Stadium Live', 'Matchday Extra', 'Court Central', 'Race Track', 'Football Plus', 'Tennis Live', 'Sports One', 'Arena 2', 'Fight Night'],
  Entertainment: ['Al Hayat', 'Cinema One', 'Drama Plus', 'Music Live', 'Evening Mix', 'Star TV', 'Prime Time', 'Comedy Central', 'Showcase', 'Now TV'],
  Kids: ['Kids Planet', 'Tiny Toons', 'Junior World', 'Cartoon Club', 'Happy Kids', 'Mini Movies', 'Space Rangers', 'Storytime', 'Wonder Kids', 'Learning Lab'],
  Movies: ['Cinema One', 'Action Max', 'Classic Screen', 'Movie Time', 'Arabic Movies', 'Premiere Plus', 'Film House', 'Thriller TV', 'Family Cinema', 'Indie Screen'],
  Music: ['Music Live', 'Soundstage', 'Arabic Hits', 'Retro Beats', 'Jazz Lounge', 'Pop Now', 'Rock Stage', 'Classical FM', 'Music Box', 'Dance Floor'],
  Documentary: ['World Documentary', 'Culture East', 'Wild Earth', 'History Vault', 'Science Today', 'Travel Now', 'Nature World', 'Discovery Zone', 'Planet Life', 'Archive TV'],
};
const liveShelves = Object.entries(channelsByGroup).map(([title, items]) => ({ title, items }));
const lastWatched = ['News 24', 'Sports Arena', 'Al Hayat'];
const categoryStats = {
  movies: {
    'Arabic Cinema': { visits: 58, newestContent: 18, lastUpdatedAt: '2026-09-20T10:24:00Z' }, 'Action & Adventure': { visits: 91, newestContent: 11, lastUpdatedAt: '2026-09-22T08:10:00Z' }, Drama: { visits: 76, newestContent: 15, lastUpdatedAt: '2026-09-19T17:42:00Z' }, Comedy: { visits: 34, newestContent: 4, lastUpdatedAt: '2026-09-21T13:05:00Z' }, 'Crime & Mystery': { visits: 104, newestContent: 20, lastUpdatedAt: '2026-09-18T11:38:00Z' }, 'Science Fiction': { visits: 83, newestContent: 16, lastUpdatedAt: '2026-09-22T16:31:00Z' }, Family: { visits: 42, newestContent: 7, lastUpdatedAt: '2026-09-20T19:14:00Z' }, Documentary: { visits: 29, newestContent: 9, lastUpdatedAt: '2026-09-21T07:52:00Z' },
  },
  series: {
    'Arabic Drama': { visits: 96, newestContent: 19, lastUpdatedAt: '2026-09-21T09:16:00Z' }, 'Crime Thrillers': { visits: 107, newestContent: 14, lastUpdatedAt: '2026-09-18T15:40:00Z' }, 'Sci-Fi & Fantasy': { visits: 88, newestContent: 17, lastUpdatedAt: '2026-09-22T12:08:00Z' }, Comedy: { visits: 37, newestContent: 5, lastUpdatedAt: '2026-09-20T18:55:00Z' }, Historical: { visits: 44, newestContent: 8, lastUpdatedAt: '2026-09-19T10:22:00Z' }, Romance: { visits: 63, newestContent: 12, lastUpdatedAt: '2026-09-21T21:03:00Z' }, 'Documentary Series': { visits: 31, newestContent: 10, lastUpdatedAt: '2026-09-22T06:47:00Z' }, 'Kids & Family': { visits: 25, newestContent: 6, lastUpdatedAt: '2026-09-20T08:34:00Z' },
  },
  live: {
    News: { visits: 112, newestContent: 12, lastUpdatedAt: '2026-09-22T14:12:00Z' }, Sports: { visits: 98, newestContent: 17, lastUpdatedAt: '2026-09-20T11:26:00Z' }, Entertainment: { visits: 73, newestContent: 10, lastUpdatedAt: '2026-09-21T16:48:00Z' }, Kids: { visits: 21, newestContent: 5, lastUpdatedAt: '2026-09-19T09:05:00Z' }, Movies: { visits: 57, newestContent: 9, lastUpdatedAt: '2026-09-22T07:39:00Z' }, Music: { visits: 39, newestContent: 7, lastUpdatedAt: '2026-09-20T22:17:00Z' }, Documentary: { visits: 28, newestContent: 14, lastUpdatedAt: '2026-09-21T05:51:00Z' },
  },
};
const seasons = ['Season 1', 'Season 2', 'Season 3'];
const spotlight = [...movies, ...series];
let slide = 0;
let timer;
let selectedSeries = series[0];
let selectedSeason = 'Season 2';
let activeScreen = 'my';
let openShelf = null;
let query = '';
let suppressCardClick = false;
const categorySorts = { movies: 'visited', series: 'visited', live: 'visited' };

function art(label, type = 'poster') {
  return `<div class="art ${type}" aria-hidden="true"><span>${label.toUpperCase()}</span></div>`;
}

function cards(items, meta, type = 'poster', layout = 'row', originFolder = '') {
  const containerClass = layout === 'grid' ? 'catalog-grid' : `row ${type === 'channel' ? 'channel-row' : ''}`;
  const tabStop = `tabindex="0" role="group" aria-label="Use Left and Right arrow keys to navigate this ${layout === 'grid' ? 'category' : 'shelf'}"`;
  return `<div class="${containerClass}" ${tabStop}>${items.map((item, index) => `
    <article class="card ${type}" tabindex="-1" data-play="${item}" data-type="${type}" data-catalog-type="${meta}" data-origin-folder="${originFolder}">
      ${art(item, type)}
      <footer><b>${item}</b><small>${meta}${meta === 'Movie' && index === 0 ? ' · 42 min left' : ''}</small></footer>
    </article>`).join('')}</div>`;
}

function shelf(title, items, meta, type = 'poster') {
  return `<section class="shelf" data-shelf="${title}"><div class="shelf-heading"><h2><button class="shelf-title" data-open-shelf="${title}">${title}</button></h2><span class="muted">${items.length} ${type === 'channel' ? 'channels' : 'titles'}</span></div>${cards(items, meta, type, 'row', title)}</section>`;
}

function shelvesForQuery(shelves, normalizedQuery, meta, type) {
  return shelves.map(({ title, items }) => {
    const titleMatches = normalizedQuery && title.toLowerCase().includes(normalizedQuery);
    const filteredItems = normalizedQuery ? items.filter((item) => item.toLowerCase().includes(normalizedQuery)) : items;
    if (normalizedQuery && !titleMatches && filteredItems.length === 0) return '';
    return shelf(title, titleMatches ? items : filteredItems, meta, type);
  }).join('');
}

function sortedCategories(screen, shelves) {
  const stats = categoryStats[screen];
  const sort = categorySorts[screen];
  return [...shelves].sort((left, right) => {
    if (sort === 'alphabetical-asc') return left.title.localeCompare(right.title);
    if (sort === 'alphabetical-desc') return right.title.localeCompare(left.title);
    if (sort === 'recently-updated') return stats[right.title].lastUpdatedAt.localeCompare(stats[left.title].lastUpdatedAt);
    const stat = sort === 'recently-added' ? 'newestContent' : 'visits';
    return stats[right.title][stat] - stats[left.title][stat];
  });
}

function categorySortControl(screen) {
  const sort = categorySorts[screen];
  return `<div class="category-sort"><label for="${screen}-category-sort">Sort categories</label><select id="${screen}-category-sort" data-category-sort="${screen}" aria-label="Sort category shelves">
    <option value="visited" ${sort === 'visited' ? 'selected' : ''}>Most visited</option>
    <option value="alphabetical-asc" ${sort === 'alphabetical-asc' ? 'selected' : ''}>Alphabetical (A-Z)</option>
    <option value="alphabetical-desc" ${sort === 'alphabetical-desc' ? 'selected' : ''}>Alphabetical (Z-A)</option>
    <option value="recently-added" ${sort === 'recently-added' ? 'selected' : ''}>Recently added</option>
    <option value="recently-updated" ${sort === 'recently-updated' ? 'selected' : ''}>Recently updated</option>
  </select></div>`;
}

function filterShelves(fixedShelves, categoryShelves, screen, type = 'poster', meta = type === 'channel' ? 'Live channel' : 'Movie') {
  const normalizedQuery = query.trim().toLowerCase();
  const shelves = [...fixedShelves, ...categoryShelves];
  if (openShelf) {
    const selectedShelf = shelves.find((entry) => entry.title === openShelf);
    if (!selectedShelf) return '<p class="empty-state muted">This category is no longer available.</p>';
    const titleMatches = normalizedQuery && selectedShelf.title.toLowerCase().includes(normalizedQuery);
    const visibleItems = titleMatches ? selectedShelf.items : selectedShelf.items.filter((item) => item.toLowerCase().includes(normalizedQuery));
    return `<section class="category-view"><div class="category-heading"><button class="back-link" data-back-shelves aria-label="Back to all shelves">← All categories</button><div><small class="eyebrow">${type === 'channel' ? 'CHANNEL GROUP' : 'CATEGORY'}</small><h2>${selectedShelf.title}</h2></div><span class="muted">${visibleItems.length} ${type === 'channel' ? 'channels' : 'titles'}</span></div>${visibleItems.length ? cards(visibleItems, meta, type, 'grid', selectedShelf.title) : '<p class="empty-state muted">No matches in this category.</p>'}</section>`;
  }
  const fixedContent = shelvesForQuery(fixedShelves, normalizedQuery, meta, type);
  const categoryContent = shelvesForQuery(sortedCategories(screen, categoryShelves), normalizedQuery, meta, type);
  if (!fixedContent && !categoryContent) return '<p class="empty-state muted">No matches in this catalog.</p>';
  return `${fixedContent}${categorySortControl(screen)}${categoryContent || '<p class="empty-state muted">No matching categories.</p>'}`;
}

function spotlightView() {
  const item = spotlight[slide];
  const isSeries = series.includes(item);
  return `<section class="spotlight" id="spotlight">
    ${art(item, 'spotlight-art')}
    <div class="spot-copy"><small class="eyebrow">TVIVO SPOTLIGHT · ${isSeries ? 'SERIES' : 'MOVIE'}</small><h1>${item}</h1><p class="muted">${isSeries ? 'Season 2 · Episode 4' : 'Feature film · 2026'}</p><button class="action" data-detail="${isSeries ? 'series' : 'movie'}" data-title="${item}">${isSeries ? 'Open series' : 'Open movie'}</button></div>
    <div class="spot-controls"><button data-prev aria-label="Previous spotlight">‹</button><span>${spotlight.map((_, index) => index === slide ? '●' : '○').join(' ')}</span><button data-next aria-label="Next spotlight">›</button></div>
  </section>`;
}

function bindSpotlight() {
  const element = document.querySelector('#spotlight');
  if (!element) return;
  element.addEventListener('mouseenter', () => clearInterval(timer));
  element.addEventListener('mouseleave', startSpotlight);
  element.addEventListener('focusin', () => clearInterval(timer));
  element.addEventListener('focusout', startSpotlight);
}

function startSpotlight() {
  clearInterval(timer);
  timer = setInterval(() => {
    slide = (slide + 1) % spotlight.length;
    const element = document.querySelector('#spotlight');
    if (element) {
      element.outerHTML = spotlightView();
      bindSpotlight();
    }
  }, 7000);
}

function topFolders(categories, screen, count) {
  const stats = categoryStats[screen];
  return [...categories].sort((left, right) => stats[right.title].visits - stats[left.title].visits).slice(0, count);
}

function home() {
  clearInterval(timer);
  const topMovieFolders = topFolders(movieCategories, 'movies', 3);
  const topSeriesFolders = topFolders(seriesCategories, 'series', 3);
  app.innerHTML = `<div class="page-intro"><div><small class="eyebrow">YOUR LIBRARY</small><h1>My Tvivo</h1></div></div>
    ${spotlightView()}
    ${shelf('Continue watching', ['The Last Signal', 'Northline', 'Signal House'], 'Movie / Episode')}
    ${topMovieFolders.map((folder) => shelf(folder.title, folder.items, 'Movie')).join('')}
    ${topSeriesFolders.map((folder) => shelf(folder.title, folder.items, 'Series')).join('')}`;
  bindSpotlight();
  startSpotlight();
}

function browseMovies() {
  app.innerHTML = `<div class="page-intro"><div><small class="eyebrow">CATALOG · MOVIES</small><h1>Movies</h1></div></div>
    ${filterShelves(movieShelves.slice(0, 3), movieCategories, 'movies', 'poster', 'Movie')}`;
}

function seriesBrowse() {
  app.innerHTML = `<div class="page-intro"><div><small class="eyebrow">CATALOG · SERIES</small><h1>Series</h1></div></div>
    ${filterShelves(seriesShelves.slice(0, 3), seriesCategories, 'series', 'poster', 'Series')}`;
}

function seriesDetail(title = selectedSeries) {
  selectedSeries = title;
  const normalizedQuery = query.trim().toLowerCase();
  const episodes = Array.from({ length: 6 }, (_, index) => index + 1).filter((number) => !normalizedQuery || `episode ${number} ${title}`.toLowerCase().includes(normalizedQuery));
  app.innerHTML = `<div class="detail-header">${art(title, 'detail-art')}<div><small class="eyebrow">SERIES</small><h1>${title}</h1><p class="muted">A slow-burn mystery about the signals we follow and the stories we leave behind.</p><button class="action" data-play="Episode 4" data-content-type="series-episode" data-series-title="${title}">Resume episode</button></div></div>
    <section class="episode-browser"><div class="section-heading"><h2>Episodes</h2><div class="season-tabs">${seasons.map((season) => `<button class="${season === selectedSeason ? 'selected' : ''}" data-season="${season}">${season}</button>`).join('')}</div></div>
      <div class="episode-list">${episodes.map((number) => `<button class="episode" data-play="Episode ${number}" data-content-type="series-episode" data-series-title="${title}"><span class="episode-number">${String(number).padStart(2, '0')}</span><span><b>Episode ${number}</b><small class="muted">${number === 4 ? 'The return signal · 48 min · 12 min left' : 'New chapter · 44 min'}</small></span><span class="play-mark">▶</span></button>`).join('') || '<p class="empty-state muted">No matching episodes.</p>'}</div>
    </section>`;
}

function live() {
  app.innerHTML = `<div class="page-intro"><div><small class="eyebrow">CHANNEL GUIDE</small><h1>Live TV</h1></div><span class="live-status"><i></i> Live now</span></div>
    ${filterShelves([{ title: 'Last watched', items: lastWatched }], liveShelves, 'live', 'channel', 'Live channel')}`;
}

function account() {
  app.innerHTML = `<div class="page-intro"><div><small class="eyebrow">PROFILE</small><h1>Account & Settings</h1></div><span class="account-badge">● Connected</span></div>
    <div class="settings-grid"><section class="settings-card account-card"><div class="avatar">H</div><div><small class="eyebrow">CURRENT USER</small><h2>Hoda</h2><p class="muted">Signed in to your Tvivo account</p></div><button class="quiet-button">Switch account</button></section>
      <section class="settings-card"><small class="eyebrow">SUBSCRIPTION</small><dl><div><dt>Status</dt><dd class="good">Active</dd></div><div><dt>Expires</dt><dd>31 Dec 2026</dd></div><div><dt>Max connections</dt><dd>1</dd></div></dl></section>
      <section class="settings-card"><small class="eyebrow">PREFERENCES</small><button class="setting-row">Appearance <span>Dark signal theme ·</span></button><button class="setting-row">Language <span>English ·</span></button><button class="setting-row">Autoplay <span>On ·</span></button></section>
      <section class="settings-card danger-card"><div><small class="eyebrow">SESSION</small><h2>Leave Tvivo</h2><p class="muted">Sign out on this device or close the app.</p></div><div class="button-row"><button class="quiet-button">Sign out</button><button class="action">Exit Tvivo</button></div></section>
    </div>`;
}

function splash() {
  clearInterval(timer);
  app.innerHTML = `<section class="splash"><div class="splash-mark">TVIVO</div><p>Find your next signal.</p><div class="loading-line"><i></i></div><small class="muted">Opening your library</small></section>`;
  setTimeout(() => render('my'), 1100);
}

function folderItemsFor(contentType, originFolder, currentTitle) {
  const sources = contentType === 'live'
    ? [{ title: 'Last watched', items: lastWatched }, ...liveShelves]
    : movieShelves;
  const fallback = contentType === 'live'
    ? { title: 'Recently added', items: liveShelves[0].items }
    : movieShelves.find((folder) => folder.title === 'Recently added');
  const folder = sources.find((entry) => entry.title === originFolder) || fallback;
  return { title: folder.title, items: folder.items.filter((item) => item !== currentTitle) };
}

function updatePlayerMetadata(title, subtitle) {
  document.querySelector('#player-title').textContent = title;
  document.querySelector('#player-subtitle').textContent = subtitle;
}

function player({ title = 'Northline', contentType = 'movie', originFolder = '' } = {}) {
  clearInterval(timer);
  const isSeriesEpisode = contentType === 'series-episode';
  const seriesTitle = contentType === 'series-episode' ? selectedSeries : '';
  const sidePanel = isSeriesEpisode
    ? `<aside class="episodes"><h2>${selectedSeason}</h2>${Array.from({ length: 8 }, (_, index) => {
      const episodeTitle = `Episode ${index + 1}`;
      return `<button class="episode ${episodeTitle === title ? 'current' : ''}" data-play="${episodeTitle}" data-content-type="series-episode" data-series-title="${seriesTitle}">${episodeTitle}<small> · ${episodeTitle === title ? 'Playing' : 'Available'}</small></button>`;
    }).join('')}</aside>`
    : (() => {
      const folder = folderItemsFor(contentType, originFolder, title);
      const catalogType = contentType === 'live' ? 'Live channel' : 'Movie';
      return `<aside class="player-side-list more-from-folder"><h2>More from ${folder.title}</h2><p class="muted">Other ${contentType === 'live' ? 'channels' : 'titles'} in this folder</p>${folder.items.map((item) => `<button class="episode" data-play="${item}" data-content-type="${contentType}" data-origin-folder="${folder.title}" data-catalog-type="${catalogType}">${item}<small> · ${contentType === 'live' ? 'Live now' : 'Available'}</small></button>`).join('')}</aside>`;
    })();
  app.innerHTML = `<div class="player"><div><div class="video"><span>PLAYER</span></div><h1 id="player-title"></h1><p id="player-subtitle" class="muted"></p></div>${sidePanel}</div>`;
  updatePlayerMetadata(title, isSeriesEpisode ? `${seriesTitle} · ${selectedSeason} · Playing` : contentType === 'live' ? 'Live now' : 'Playing');
}

function render(id, resetShelf = true) {
  clearInterval(timer);
  if (resetShelf) openShelf = null;
  activeScreen = id;
  if (id === 'my') home();
  else if (id === 'movies') browseMovies();
  else if (id === 'series') seriesBrowse();
  else if (id === 'live') live();
  else if (id === 'account') account();
  else if (id === 'splash') splash();
  else home();
  nav.forEach((item) => item.classList.toggle('active', item.dataset.target === id));
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function refreshCurrentScreen() {
  if (activeScreen === 'movies') browseMovies();
  else if (activeScreen === 'series') seriesBrowse();
  else if (activeScreen === 'live') live();
  else if (activeScreen === 'series-detail') seriesDetail();
}

searchInput.addEventListener('input', () => {
  query = searchInput.value;
  clearSearch.hidden = !query;
  refreshCurrentScreen();
});

document.addEventListener('change', (event) => {
  const sortControl = event.target.closest('[data-category-sort]');
  if (!sortControl) return;
  categorySorts[sortControl.dataset.categorySort] = sortControl.value;
  refreshCurrentScreen();
});

clearSearch.addEventListener('click', () => {
  searchInput.value = '';
  query = '';
  clearSearch.hidden = true;
  refreshCurrentScreen();
  searchInput.focus();
});

document.addEventListener('click', (event) => {
  const target = event.target.closest('[data-target]');
  if (target) render(target.dataset.target);
  if (event.target.closest('[data-next]')) { slide = (slide + 1) % spotlight.length; home(); }
  if (event.target.closest('[data-prev]')) { slide = (slide + spotlight.length - 1) % spotlight.length; home(); }
  const detail = event.target.closest('[data-detail]');
  if (detail) {
    if (detail.dataset.detail === 'series') { activeScreen = 'series-detail'; seriesDetail(detail.dataset.title); }
    else player({ title: detail.dataset.title, contentType: 'movie', originFolder: 'Recently added' });
  }
  const shelfTitle = event.target.closest('[data-open-shelf]');
  if (shelfTitle) {
    openShelf = shelfTitle.dataset.openShelf;
    refreshCurrentScreen();
  }
  if (event.target.closest('[data-back-shelves]')) {
    openShelf = null;
    refreshCurrentScreen();
  }
  const season = event.target.closest('[data-season]');
  if (season) { selectedSeason = season.dataset.season; seriesDetail(); }
  const play = event.target.closest('[data-play]');
  if (play && !suppressCardClick) activateCard(play);
});

function activateCard(card) {
  if (card.dataset.catalogType === 'Series') { activeScreen = 'series-detail'; seriesDetail(card.dataset.play); }
  else {
    const contentType = card.dataset.contentType || (card.dataset.catalogType === 'Live channel' ? 'live' : 'movie');
    if (contentType === 'series-episode') selectedSeries = card.dataset.seriesTitle || selectedSeries;
    player({ title: card.dataset.play, contentType, originFolder: card.dataset.originFolder });
  }
}

document.addEventListener('keydown', (event) => {
  if (['ArrowLeft', 'ArrowRight'].includes(event.key)) {
    const focusedElement = document.activeElement;
    const container = focusedElement?.closest('.row, .catalog-grid');
    if (!container) return;
    const cardList = [...container.querySelectorAll('.card')];
    const focusedCard = focusedElement.closest('.card');
    const direction = event.key === 'ArrowRight' ? 1 : -1;
    const nextCard = focusedCard
      ? cardList[cardList.indexOf(focusedCard) + direction]
      : cardList[direction === 1 ? 0 : cardList.length - 1];
    if (!nextCard) return;
    event.preventDefault();
    nextCard.focus();
    nextCard.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  }
  if (['Enter', ' '].includes(event.key) && event.target.closest('.card')) {
    event.preventDefault();
    activateCard(event.target.closest('.card'));
  }
  if (event.key === 'Escape' && document.activeElement === searchInput) {
    searchInput.value = '';
    query = '';
    clearSearch.hidden = true;
    refreshCurrentScreen();
  }
});

document.addEventListener('pointerdown', (event) => {
  const row = event.target.closest('.row');
  if (!row || event.button !== 0) return;
  event.preventDefault();
  const initialCard = event.target.closest('.card');
  const startX = event.clientX;
  const startScroll = row.scrollLeft;
  let moved = false;
  row.setPointerCapture(event.pointerId);
  const move = (moveEvent) => {
    const delta = moveEvent.clientX - startX;
    if (Math.abs(delta) >= 8) moved = true;
    row.scrollLeft = startScroll - delta;
  };
  const end = (endEvent) => {
    row.removeEventListener('pointermove', move);
    row.removeEventListener('pointerup', end);
    row.removeEventListener('pointercancel', end);
    if (moved) {
      suppressCardClick = true;
      setTimeout(() => { suppressCardClick = false; }, 0);
    } else if (endEvent.type === 'pointerup' && initialCard) {
      suppressCardClick = true;
      activateCard(initialCard);
      setTimeout(() => { suppressCardClick = false; }, 0);
    }
  };
  row.addEventListener('pointermove', move);
  row.addEventListener('pointerup', end);
  row.addEventListener('pointercancel', end);
});

render('splash');
