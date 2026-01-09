import axios from 'axios';
import { getToken, updateToken } from './auth';
export class ApiClient {
    constructor(baseURL = '') {
        this.http = axios.create({ baseURL });
        this.http.interceptors.request.use(async (config) => {
            try {
                await updateToken(5);
                const token = getToken();
                if (token) {
                    config.headers.Authorization = `Bearer ${token}`;
                }
            }
            catch (error) {
                console.error('Failed to refresh token', error);
            }
            return config;
        });
    }
    async getMedia(select, referenceMediaAccessKey) {
        const res = await this.http.get('/api/media', {
            params: { select, referenceMediaAccessKey }
        });
        return res.data;
    }
    async getMediaByKey(mediaAccessKey) {
        const res = await this.http.get(`/api/media/${encodeURIComponent(mediaAccessKey)}`);
        return res.data;
    }
    mediaNormalizedUrl(mediaAccessKey) {
        return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/normalized`;
    }
    mediaMiniatureUrl(mediaAccessKey) {
        return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/miniature`;
    }
    mediaOriginalUrl(mediaAccessKey) {
        return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/original`;
    }
    async listEvents() {
        return await this.fetchNdjson('/api/events');
    }
    async updateEvent(eventId, body) {
        await this.http.put(`/api/event/${encodeURIComponent(eventId)}`, body);
    }
    async getState(originalId) {
        const res = await this.http.get(`/api/state/${encodeURIComponent(originalId)}`);
        return res.data;
    }
    async createEvent(body) {
        const res = await this.http.post('/api/event', body);
        return res.data;
    }
    async listOwners() {
        return await this.fetchNdjson('/api/owners');
    }
    async listStores() {
        return await this.fetchNdjson('/api/stores');
    }
    async synchronizeStatus() {
        const res = await this.http.get('/api/admin/synchronize');
        return res.data;
    }
    async synchronizeStart() {
        await this.http.put('/api/admin/synchronize');
    }
    async mediasWithLocations(onItem) {
        await this.fetchNdjsonStream('/api/medias?filterHasLocation=true', onItem);
    }
    async fetchNdjson(url) {
        // Fallback non-streaming NDJSON reader
        const res = await this.http.get(url, { responseType: 'text' });
        const items = [];
        const lines = res.data.split(/\r?\n/);
        for (const line of lines) {
            if (!line.trim())
                continue;
            try {
                items.push(JSON.parse(line));
            }
            catch { }
        }
        return items;
    }
    async fetchNdjsonStream(url, onItem) {
        if (!('fetch' in window)) {
            const all = await this.fetchNdjson(url);
            all.forEach(onItem);
            return;
        }
        const res = await fetch(url);
        const reader = res.body?.getReader();
        if (!reader)
            return;
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done)
                break;
            buffer += decoder.decode(value, { stream: true });
            let idx;
            while ((idx = buffer.indexOf('\n')) >= 0) {
                const line = buffer.slice(0, idx);
                buffer = buffer.slice(idx + 1);
                if (line.trim().length === 0)
                    continue;
                try {
                    onItem(JSON.parse(line));
                }
                catch { }
            }
        }
        if (buffer.trim().length > 0) {
            try {
                onItem(JSON.parse(buffer));
            }
            catch { }
        }
    }
}
