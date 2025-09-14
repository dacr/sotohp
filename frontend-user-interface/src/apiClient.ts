import axios, { AxiosInstance } from 'axios'
import type { Media, MediaSelector, Event as SEvent, Owner, Store } from './types'

export class ApiClient {
  private http: AxiosInstance

  constructor(baseURL: string = '') {
    this.http = axios.create({ baseURL })
  }

  async getMedia(select: MediaSelector, referenceMediaAccessKey?: string): Promise<Media> {
    const res = await this.http.get<Media>('/api/media', {
      params: { select, referenceMediaAccessKey }
    })
    return res.data
  }

  async getMediaByKey(mediaAccessKey: string): Promise<Media> {
    const res = await this.http.get<Media>(`/api/media/${encodeURIComponent(mediaAccessKey)}`)
    return res.data
  }

  mediaNormalizedUrl(mediaAccessKey: string): string {
    return `/api/media/${encodeURIComponent(mediaAccessKey)}/normalized`
  }

  async listEvents(): Promise<SEvent[]> {
    return await this.fetchNdjson<SEvent>('/api/events')
  }

  async getState(originalId: string): Promise<{ originalId: string; originalAddedOn: string; mediaAccessKey: string }> {
    const res = await this.http.get(`/api/state/${encodeURIComponent(originalId)}`)
    return res.data
  }

  async createEvent(name: string): Promise<SEvent> {
    const res = await this.http.post<SEvent>('/api/event', { name })
    return res.data
  }

  async listOwners(): Promise<Owner[]> {
    return await this.fetchNdjson<Owner>('/api/owners')
  }

  async listStores(): Promise<Store[]> {
    return await this.fetchNdjson<Store>('/api/stores')
  }

  async synchronize(): Promise<void> {
    await this.http.get('/api/admin/synchronize')
  }

  async mediasWithLocations(onItem: (m: Media) => void): Promise<void> {
    await this.fetchNdjsonStream('/api/medias?filterHasLocation=true', onItem)
  }

  private async fetchNdjson<T>(url: string): Promise<T[]> {
    // Fallback non-streaming NDJSON reader
    const res = await this.http.get(url, { responseType: 'text' })
    const items: T[] = []
    const lines = (res.data as string).split(/\r?\n/)
    for (const line of lines) {
      if (!line.trim()) continue
      try { items.push(JSON.parse(line) as T) } catch {}
    }
    return items
  }

  private async fetchNdjsonStream<T>(url: string, onItem: (t: T) => void): Promise<void> {
    if (!('fetch' in window)) {
      const all = await this.fetchNdjson<T>(url)
      all.forEach(onItem)
      return
    }
    const res = await fetch(url)
    const reader = res.body?.getReader()
    if (!reader) return
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx: number
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 1)
        if (line.trim().length === 0) continue
        try { onItem(JSON.parse(line) as T) } catch {}
      }
    }
    if (buffer.trim().length > 0) {
      try { onItem(JSON.parse(buffer) as T) } catch {}
    }
  }
}
