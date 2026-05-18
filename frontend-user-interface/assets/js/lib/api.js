/**
 * Typed-ish wrapper around the backend REST API.
 *
 * Auth is injected — the class doesn't know about Keycloak directly. Pass an
 * `auth` object with at least `getToken()` to attach a bearer token to every
 * request. Optional `refreshToken()` is awaited before each call (use it to
 * silently refresh nearly-expired tokens). Optional `onToken(token)` is called
 * with the live token after each refresh — `app.js` uses it to forward the
 * token to the Service Worker.
 *
 *   new ApiClient('', {
 *     getToken:     () => keycloak.token,
 *     refreshToken: () => keycloak.updateToken(30),
 *     onToken:      (t) => sendTokenToSW(t),
 *   });
 *
 * NDJSON helpers fall back to native fetch because axios doesn't stream cleanly.
 */
export class ApiClient {
  constructor(baseURL = '', auth = {}) {
    this.auth = auth;
    this.http = axios.create({ baseURL });
    this.http.interceptors.request.use(async (config) => {
      const token = await this._ensureToken();
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
        if (!config.params) config.params = {};
        config.params.token = token;
      }
      return config;
    });
  }

  async _ensureToken() {
    if (typeof this.auth.refreshToken === 'function') {
      try { await this.auth.refreshToken(); } catch {}
    }
    const token = this._token();
    if (token && typeof this.auth.onToken === 'function') {
      try { this.auth.onToken(token); } catch {}
    }
    return token;
  }

  _token() {
    return typeof this.auth.getToken === 'function' ? (this.auth.getToken() || null) : null;
  }

  _tokenQuery() {
    const t = this._token();
    return t ? `?token=${encodeURIComponent(t)}` : '';
  }

  // -- Direct image URLs (need token in the query string for <img> tags) -----
  mediaNormalizedUrl(mediaAccessKey) { return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/normalized${this._tokenQuery()}`; }
  mediaMiniatureUrl(mediaAccessKey)  { return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/miniature${this._tokenQuery()}`; }
  mediaOriginalUrl(mediaAccessKey)   { return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/original${this._tokenQuery()}`; }
  faceImageUrl(faceId)               { return `/api/face/${encodeURIComponent(faceId)}/content${this._tokenQuery()}`; }

  // -- Media ------------------------------------------------------------------
  async getMedia(select, referenceMediaAccessKey, referenceMediaTimestamp) {
    const params = { select };
    if (referenceMediaAccessKey) params.referenceMediaAccessKey = referenceMediaAccessKey;
    if (referenceMediaTimestamp) params.referenceMediaTimestamp = referenceMediaTimestamp;
    const res = await this.http.get('/api/media', { params });
    return res.data;
  }
  async getMediaByKey(mediaAccessKey) { const res = await this.http.get(`/api/media/${encodeURIComponent(mediaAccessKey)}`); return res.data; }
  async updateMedia(mediaAccessKey, body) { await this.http.put(`/api/media/${encodeURIComponent(mediaAccessKey)}`, body); }
  async updateMediaStarred(mediaAccessKey, state) { await this.http.put(`/api/media/${encodeURIComponent(mediaAccessKey)}/starred`, null, { params: { state } }); }
  async getMediaFaces(mediaAccessKey) { const res = await this.http.get(`/api/media/${encodeURIComponent(mediaAccessKey)}/faces`); return res.data; }
  async mediasWithLocations(onItem) { await this._fetchNdjsonStream('/api/medias?filterHasLocation=true', onItem); }

  // -- State ------------------------------------------------------------------
  async getState(originalId) { const res = await this.http.get(`/api/state/${encodeURIComponent(originalId)}`); return res.data; }

  // -- Events -----------------------------------------------------------------
  async listEvents() { return this._fetchNdjson('/api/events'); }
  async createEvent(body) { const res = await this.http.post('/api/event', body); return res.data; }
  async updateEvent(eventId, body) { await this.http.put(`/api/event/${encodeURIComponent(eventId)}`, body); }
  async setEventCover(eventId, mediaAccessKey) { await this.http.put(`/api/event/${encodeURIComponent(eventId)}/cover/${encodeURIComponent(mediaAccessKey)}`); }

  // -- Owners -----------------------------------------------------------------
  async listOwners() { return this._fetchNdjson('/api/owners'); }
  async getOwner(ownerId) { const res = await this.http.get(`/api/owner/${encodeURIComponent(ownerId)}`); return res.data; }
  async updateOwner(ownerId, body) { await this.http.put(`/api/owner/${encodeURIComponent(ownerId)}`, body); }
  async createOwner(body) { const res = await this.http.post('/api/owner', body); return res.data; }
  async setOwnerCover(ownerId, mediaAccessKey) { await this.http.put(`/api/owner/${encodeURIComponent(ownerId)}/cover/${encodeURIComponent(mediaAccessKey)}`); }

  // -- Persons ----------------------------------------------------------------
  async listPersons() { return this._fetchNdjson('/api/persons'); }
  async getPerson(personId) { const res = await this.http.get(`/api/person/${encodeURIComponent(personId)}`); return res.data; }
  async createPerson(body) { const res = await this.http.post('/api/person', body); return res.data; }
  async updatePerson(personId, body) { await this.http.put(`/api/person/${encodeURIComponent(personId)}`, body); }
  async deletePerson(personId) { await this.http.delete(`/api/person/${encodeURIComponent(personId)}`); }
  async updatePersonFace(personId, faceId) { await this.http.put(`/api/person/${encodeURIComponent(personId)}/face/${encodeURIComponent(faceId)}`); }
  async listPersonFaces(personId) { return this._fetchNdjson(`/api/person/${encodeURIComponent(personId)}/faces`); }

  // -- Faces ------------------------------------------------------------------
  async listFaces() { return this._fetchNdjson('/api/faces'); }
  async getFace(faceId) { const res = await this.http.get(`/api/face/${encodeURIComponent(faceId)}`); return res.data; }
  async createFace(body) { const res = await this.http.post('/api/face', body); return res.data; }
  async deleteFace(faceId) { await this.http.delete(`/api/face/${encodeURIComponent(faceId)}`); }
  async setFacePerson(faceId, personId) { await this.http.put(`/api/face/${encodeURIComponent(faceId)}/person/${encodeURIComponent(personId)}`, null); }
  async removeFacePerson(faceId) { await this.http.delete(`/api/face/${encodeURIComponent(faceId)}/person`); }

  // -- Stores -----------------------------------------------------------------
  async listStores() { return this._fetchNdjson('/api/stores'); }
  async getStore(storeId) { const res = await this.http.get(`/api/store/${encodeURIComponent(storeId)}`); return res.data; }
  async updateStore(storeId, body) { await this.http.put(`/api/store/${encodeURIComponent(storeId)}`, body); }
  async createStore(body) { const res = await this.http.post('/api/store', body); return res.data; }

  // -- Portfolios -------------------------------------------------------------
  async listPortfolios() { return this._fetchNdjson('/api/portfolios'); }
  async getPortfolio(portfolioId) { const res = await this.http.get(`/api/portfolio/${encodeURIComponent(portfolioId)}`); return res.data; }
  async createPortfolio(body) { const res = await this.http.post('/api/portfolio', body); return res.data; }
  async updatePortfolio(portfolioId, body) { await this.http.put(`/api/portfolio/${encodeURIComponent(portfolioId)}`, body); }
  async deletePortfolio(portfolioId) { await this.http.delete(`/api/portfolio/${encodeURIComponent(portfolioId)}`); }
  async addPortfolioAsset(portfolioId, body) { const res = await this.http.post(`/api/portfolio/${encodeURIComponent(portfolioId)}/asset`, body); return res.data; }
  async updatePortfolioAsset(portfolioId, oldAsset, newAsset) {
    const res = await this.http.put(`/api/portfolio/${encodeURIComponent(portfolioId)}/asset`, { oldAsset, newAsset });
    return res.data;
  }
  async removePortfolioAsset(portfolioId, body) { await this.http.delete(`/api/portfolio/${encodeURIComponent(portfolioId)}/asset`, { data: body }); }

  // -- Sync admin -------------------------------------------------------------
  async synchronize(days) {
    const params = {};
    if (days) params.addedThoseLastDays = days;
    await this.http.post('/api/admin/synchronize', null, { params });
  }
  async synchronizeStatus() { const res = await this.http.get('/api/admin/synchronize'); return res.data; }
  async synchronizeStart(addedThoseLastDays) {
    const params = {};
    if (typeof addedThoseLastDays === 'number' && Number.isFinite(addedThoseLastDays)) params.addedThoseLastDays = addedThoseLastDays;
    await this.http.put('/api/admin/synchronize', null, { params });
  }

  // -- NDJSON helpers ---------------------------------------------------------
  async _fetchNdjson(url) {
    const t = this._token() || '';
    const response = await fetch(url, { headers: { Authorization: `Bearer ${t}` } });
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    const results = [];
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (line.trim()) {
          try { results.push(JSON.parse(line)); } catch (e) { console.error('NDJSON parse error', e); }
        }
      }
    }
    if (buffer.trim()) {
      try { results.push(JSON.parse(buffer)); } catch {}
    }
    return results;
  }

  async _fetchNdjsonStream(url, onItem) {
    const t = this._token() || '';
    const response = await fetch(url, { headers: { Authorization: `Bearer ${t}` } });
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (line.trim()) {
          try { onItem(JSON.parse(line)); } catch (e) { console.error('NDJSON parse error', e); }
        }
      }
    }
    if (buffer.trim()) {
      try { onItem(JSON.parse(buffer)); } catch {}
    }
  }
}
