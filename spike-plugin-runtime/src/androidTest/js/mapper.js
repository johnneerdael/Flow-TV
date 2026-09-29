// A plugin-shaped workload: parse a YouTube Music response and map every shelf and item in it to
// page JSON. Written in ES5 so Rhino, QuickJS and the Kotlin baseline run the same algorithm.

function runsText(value) {
  if (!value || !value.runs) return '';
  var out = '';
  for (var i = 0; i < value.runs.length; i++) out += value.runs[i].text;
  return out;
}

function lastThumbnail(renderer) {
  var thumbnails = renderer && renderer.musicThumbnailRenderer &&
    renderer.musicThumbnailRenderer.thumbnail && renderer.musicThumbnailRenderer.thumbnail.thumbnails;
  return thumbnails && thumbnails.length ? thumbnails[thumbnails.length - 1].url : null;
}

function endpointId(endpoint) {
  if (!endpoint) return null;
  if (endpoint.browseEndpoint) return endpoint.browseEndpoint.browseId;
  if (endpoint.watchEndpoint) return endpoint.watchEndpoint.videoId;
  return null;
}

function twoRowItem(item) {
  return {
    title: runsText(item.title),
    subtitle: runsText(item.subtitle),
    artwork: lastThumbnail(item.thumbnailRenderer),
    id: endpointId(item.navigationEndpoint),
    wide: item.aspectRatio === 'MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9',
  };
}

function listItem(item) {
  var columns = item.flexColumns || [];
  var texts = [];
  for (var i = 0; i < columns.length; i++) {
    var column = columns[i].musicResponsiveListItemFlexColumnRenderer;
    texts.push(column ? runsText(column.text) : '');
  }
  return {
    title: texts[0] || '',
    subtitle: texts.slice(1).join(' • '),
    artwork: lastThumbnail(item.thumbnail),
    id: (item.playlistItemData && item.playlistItemData.videoId) || endpointId(item.navigationEndpoint),
    wide: false,
  };
}

var SHELF_KEYS = ['musicCarouselShelfRenderer', 'musicShelfRenderer', 'musicPlaylistShelfRenderer', 'gridRenderer'];

function shelfTitle(shelf) {
  var header = shelf.header;
  if (header && header.musicCarouselShelfBasicHeaderRenderer) return runsText(header.musicCarouselShelfBasicHeaderRenderer.title);
  if (header && header.gridHeaderRenderer) return runsText(header.gridHeaderRenderer.title);
  return runsText(shelf.title);
}

function walk(node, shelf, shelves) {
  if (node === null || typeof node !== 'object') return;
  if (Array.isArray(node)) {
    for (var i = 0; i < node.length; i++) walk(node[i], shelf, shelves);
    return;
  }
  for (var k = 0; k < SHELF_KEYS.length; k++) {
    var found = node[SHELF_KEYS[k]];
    if (found) {
      var next = { title: shelfTitle(found), items: [] };
      shelves.push(next);
      walk(found.contents || found.items, next, shelves);
      return;
    }
  }
  if (node.musicTwoRowItemRenderer) {
    if (shelf) shelf.items.push(twoRowItem(node.musicTwoRowItemRenderer));
    return;
  }
  if (node.musicResponsiveListItemRenderer) {
    if (shelf) shelf.items.push(listItem(node.musicResponsiveListItemRenderer));
    return;
  }
  for (var key in node) {
    if (Object.prototype.hasOwnProperty.call(node, key)) walk(node[key], shelf, shelves);
  }
}

function mapPage(text) {
  var shelves = [];
  walk(JSON.parse(text), null, shelves);
  return JSON.stringify({ shelves: shelves });
}
