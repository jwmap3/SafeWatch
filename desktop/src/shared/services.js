'use strict';
// The streaming services, as in the phone app (app/.../data/Services.kt). Each opens on its own website; you sign in there.
module.exports = [
  { id: 'netflix', name: 'Netflix', home: 'https://www.netflix.com/', domain: 'netflix.com' },
  { id: 'hbomax', name: 'HBO Max', home: 'https://www.hbomax.com/', domain: 'hbomax.com' },
  { id: 'prime', name: 'Prime Video', home: 'https://www.primevideo.com/', domain: 'primevideo.com' },
  { id: 'disney', name: 'Disney+', home: 'https://www.disneyplus.com/', domain: 'disneyplus.com' },
  { id: 'hulu', name: 'Hulu', home: 'https://www.hulu.com/', domain: 'hulu.com' },
  { id: 'appletv', name: 'Apple TV+', home: 'https://tv.apple.com/', domain: 'tv.apple.com' },
  { id: 'peacock', name: 'Peacock', home: 'https://www.peacocktv.com/', domain: 'peacocktv.com' },
  { id: 'paramount', name: 'Paramount+', home: 'https://www.paramountplus.com/', domain: 'paramountplus.com' },
  { id: 'tubi', name: 'Tubi', home: 'https://tubitv.com/', domain: 'tubitv.com' },
  { id: 'pluto', name: 'Pluto TV', home: 'https://pluto.tv/', domain: 'pluto.tv' },
];
