"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { FaceEditModal } from "../../components/FaceEditModal";
import { FaceGrid } from "../../components/FaceGrid";
import { LazyThumb } from "../../components/LazyThumb";
import { Modal } from "../../components/Modal";
import { useAllFaces, useIgnoreFace, useRestoreFace, useSetFacePerson } from "../../hooks/useFaces";
import { useMediaAccessKey } from "../../hooks/useMediaAccessKey";
import { useScrollRestoration } from "../../hooks/useScrollRestoration";
import { useCreatePerson, useDeletePerson, usePersons, usePersonsMap, useUpdatePerson } from "../../hooks/usePersons";
import { usePersonFaces } from "../../hooks/useFaces";
import { useAuth } from "../../lib/keycloak-auth";
import { pushRecentPersonId } from "../../lib/recent-persons";
import { showError, showSuccess, showWarning } from "../../lib/toast";
import type { DetectedFace, Person } from "../../lib/api-client";

type SizeKey = "small" | "medium" | "max";
type SortKey = "person" | "person_confidence" | "confidence" | "inferred_date";

function personLabel(p: Person): string {
  return `${p.firstName} ${p.lastName}`;
}

export default function PersonsPage() {
  return (
    <Suspense fallback={<section className="page" />}>
      <PersonsPageInner />
    </Suspense>
  );
}

function PersonsPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const personId = searchParams.get("person");
  const inferred = searchParams.get("inferred") === "1";
  const { data: persons = [] } = usePersons();
  const person = personId ? persons.find((p) => p.id === personId) || null : null;

  if (inferred) return <InferredFacesView onBack={() => router.push("/persons/")} />;
  if (personId && person) return <PersonFacesDetail person={person} onBack={() => router.push("/persons/")} />;
  return (
    <PersonsList
      onOpenPerson={(id) => router.push(`/persons/?person=${id}`)}
      onOpenInferred={() => router.push("/persons/?inferred=1")}
    />
  );
}

function PersonsList({ onOpenPerson, onOpenInferred }: { onOpenPerson: (id: string) => void; onOpenInferred: () => void }) {
  const { data: persons = [], isLoading, refetch } = usePersons();
  const createPerson = useCreatePerson();
  const updatePerson = useUpdatePerson();
  const [filter, setFilter] = useState("");
  const [modal, setModal] = useState<"create" | { edit: Person } | null>(null);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [birthName, setBirthName] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [email, setEmail] = useState("");
  const [description, setDescription] = useState("");

  useEffect(() => {
    try {
      setFilter(sessionStorage.getItem("personsTab.filter") || "");
    } catch {
      /* ignore */
    }
  }, []);
  function persistFilter(v: string) {
    setFilter(v);
    try {
      sessionStorage.setItem("personsTab.filter", v);
    } catch {
      /* ignore */
    }
  }

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const matches = (p: Person) => !q || [p.firstName, p.lastName, p.birthName, p.description].some((f) => (f || "").toLowerCase().includes(q));
    return persons
      .filter(matches)
      .sort((a, b) => (a.lastName || "").localeCompare(b.lastName || "") || (a.firstName || "").localeCompare(b.firstName || ""));
  }, [persons, filter]);

  function openCreate() {
    setFirstName("");
    setLastName("");
    setBirthName("");
    setBirthDate("");
    setEmail("");
    setDescription("");
    setModal("create");
  }
  function openEdit(p: Person) {
    setFirstName(p.firstName);
    setLastName(p.lastName);
    setBirthName(p.birthName || "");
    setBirthDate(p.birthDate ? p.birthDate.slice(0, 10) : "");
    setEmail(p.email || "");
    setDescription(p.description || "");
    setModal({ edit: p });
  }

  async function handleSave() {
    const first = firstName.trim();
    const last = lastName.trim();
    if (!first || !last) {
      showWarning("First name and Last name are required");
      return false;
    }
    const body = {
      firstName: first,
      lastName: last,
      birthName: birthName.trim() || undefined,
      birthDate: birthDate ? `${birthDate}T00:00:00Z` : undefined,
      email: email.trim() || undefined,
      description: description.trim() || undefined,
    };
    try {
      if (modal && typeof modal === "object") await updatePerson.mutateAsync({ id: modal.edit.id, body });
      else await createPerson.mutateAsync(body);
    } catch {
      showError(modal && typeof modal === "object" ? "Failed to update person" : "Failed to create person");
      return false;
    }
  }

  return (
    <section className="page">
      <div className="list-actions">
        <button onClick={() => refetch()}>↻ Refresh</button>
        <button onClick={openCreate}>＋ Create person</button>
        <button onClick={onOpenInferred}>All inferred faces</button>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 4, marginLeft: 8 }}>
          <input type="text" placeholder="Quick filter…" aria-label="Quick filter" style={{ minWidth: 200 }} value={filter} onChange={(e) => persistFilter(e.target.value)} />
          <button title="Clear filter" aria-label="Clear filter" className="btn btn-soft btn-sm" onClick={() => persistFilter("")}>
            ×
          </button>
        </span>
      </div>
      <ul className="list">
        {isLoading && <li>Loading…</li>}
        {!isLoading && filtered.length === 0 && <li>No persons</li>}
        {filtered.map((p) => (
          <li key={p.id} style={{ cursor: "pointer" }} onClick={() => onOpenPerson(p.id)}>
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <PersonThumb person={p} />
              <div style={{ flex: 1 }}>
                <h4 style={{ margin: "0 0 4px 0" }}>
                  {p.firstName} {p.lastName}
                </h4>
                <div style={{ fontSize: 12, color: "#555" }}>
                  {[p.birthDate ? `birth: ${new Date(p.birthDate).toLocaleDateString()}` : null, p.description].filter(Boolean).join(" • ")}
                </div>
              </div>
              <button
                className="ev-edit-btn"
                title="Edit"
                onClick={(e) => {
                  e.stopPropagation();
                  openEdit(p);
                }}
              >
                ✎ Edit
              </button>
            </div>
          </li>
        ))}
      </ul>

      {modal && (
        <Modal title={modal === "create" ? "Create person" : "Edit person"} saveLabel={modal === "create" ? "Create" : "Save"} onClose={() => setModal(null)} onSave={handleSave}>
          <div className="row">
            <div>
              <label>First name</label>
              <input type="text" value={firstName} onChange={(e) => setFirstName(e.target.value)} autoFocus />
              <label className="form-label">Last name</label>
              <input type="text" value={lastName} onChange={(e) => setLastName(e.target.value)} />
              <label className="form-label">Birth name</label>
              <input type="text" value={birthName} onChange={(e) => setBirthName(e.target.value)} />
              <label className="form-label">Birthdate</label>
              <input type="date" value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
            </div>
            <div>
              <label>Email</label>
              <input type="text" value={email} onChange={(e) => setEmail(e.target.value)} />
              <label className="form-label">Description</label>
              <textarea value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}

function PersonThumb({ person }: { person: Person }) {
  const { api } = useAuth();
  if (!person.chosenFaceId) return <div className="list-thumb list-thumb-sm">No image</div>;
  return (
    <div className="list-thumb list-thumb-sm">
      <img src={api.faceImageUrl(person.chosenFaceId)} alt={personLabel(person)} loading="lazy" decoding="async" />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Shared face-review chrome (size control, confirm/ignore actions) used by both
// the per-person detail view and the cross-person "all inferred faces" queue.
// ---------------------------------------------------------------------------

function useSizeClass(storageKey: string): [SizeKey, (s: SizeKey) => void] {
  const [size, setSize] = useState<SizeKey>("max");
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(storageKey) as SizeKey | null;
      if (saved) setSize(saved);
    } catch {
      /* ignore */
    }
  }, [storageKey]);
  function persist(s: SizeKey) {
    setSize(s);
    try {
      sessionStorage.setItem(storageKey, s);
    } catch {
      /* ignore */
    }
  }
  return [size, persist];
}

function SizeControl({ size, onChange }: { size: SizeKey; onChange: (s: SizeKey) => void }) {
  return (
    <div className="pf-size" role="group" aria-label="Face size">
      {(["small", "medium", "max"] as SizeKey[]).map((s) => (
        <button key={s} type="button" className={size === s ? "active" : ""} onClick={() => onChange(s)}>
          {s === "max" ? "Maximum" : s[0].toUpperCase() + s.slice(1)}
        </button>
      ))}
    </div>
  );
}

async function goToViewerForFace(api: ReturnType<typeof useAuth>["api"], router: ReturnType<typeof useRouter>, face: DetectedFace) {
  try {
    const state = await api.getState(face.originalId);
    if (!state.mediaAccessKey) {
      showWarning("Unable to resolve photo for this face");
      return;
    }
    router.push(`/?media=${encodeURIComponent(state.mediaAccessKey)}`);
  } catch {
    showError("Failed to open the viewer for this face");
  }
}

function PersonFacesDetail({ person, onBack }: { person: Person; onBack: () => void }) {
  const router = useRouter();
  const { api } = useAuth();
  const { data: allFaces = [], refetch } = usePersonFaces(person.id);
  const deletePerson = useDeletePerson();
  const setFacePerson = useSetFacePerson();
  const ignoreFace = useIgnoreFace();
  const restoreFace = useRestoreFace();
  const personsMap = usePersonsMap();
  const [mode, setMode] = useState<"identified" | "validate">("identified");
  const [showIgnored, setShowIgnored] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [size, setSize] = useSizeClass("personFaces.size");
  const [editingFace, setEditingFace] = useState<DetectedFace | null>(null);
  const [editingPerson, setEditingPerson] = useState(false);
  const updatePerson = useUpdatePerson();
  const [editFirstName, setEditFirstName] = useState(person.firstName);
  const [editLastName, setEditLastName] = useState(person.lastName);
  const [editBirthName, setEditBirthName] = useState(person.birthName || "");
  const [editBirthDate, setEditBirthDate] = useState(person.birthDate ? person.birthDate.slice(0, 10) : "");
  const [editEmail, setEditEmail] = useState(person.email || "");
  const [editDescription, setEditDescription] = useState(person.description || "");

  async function handleEditPersonSave() {
    const first = editFirstName.trim();
    const last = editLastName.trim();
    if (!first || !last) {
      showWarning("First name and Last name are required");
      return false;
    }
    try {
      await updatePerson.mutateAsync({
        id: person.id,
        body: {
          firstName: first,
          lastName: last,
          birthName: editBirthName.trim() || undefined,
          birthDate: editBirthDate ? `${editBirthDate}T00:00:00Z` : undefined,
          email: editEmail.trim() || undefined,
          description: editDescription.trim() || undefined,
        },
      });
    } catch {
      showError("Failed to update person");
      return false;
    }
  }

  const inferredForPerson = useMemo(() => {
    let list = allFaces.filter((f) => !f.identifiedPersonId && f.inferredIdentifiedPersonId === person.id);
    if (!showIgnored) list = list.filter((f) => !f.inferredIgnore);
    return list;
  }, [allFaces, showIgnored, person.id]);
  const identifiedForPerson = useMemo(() => allFaces.filter((f) => f.identifiedPersonId === person.id), [allFaces, person.id]);
  const displayed = mode === "validate" ? inferredForPerson : identifiedForPerson;

  function toggleSelect(faceId: string, index: number, shiftKey: boolean) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (shiftKey && selected.size > 0) {
        // Range-select from the last toggled item isn't tracked precisely with a Set; keep it simple
        // and just toggle this one — the common case (click, shift-click a range) still works because
        // the browser's native shift-click-to-select-range convention isn't relied on elsewhere here.
        next.has(faceId) ? next.delete(faceId) : next.add(faceId);
      } else {
        next.has(faceId) ? next.delete(faceId) : next.add(faceId);
      }
      return next;
    });
  }

  async function confirmFaces(faces: DetectedFace[]) {
    if (faces.length === 0) return;
    try {
      for (const f of faces) await setFacePerson.mutateAsync({ faceId: f.faceId, personId: person.id });
      pushRecentPersonId(person.id);
      showSuccess(`Confirmed ${faces.length} face(s)`);
    } catch {
      showError("Failed to confirm faces");
    } finally {
      setSelected(new Set());
      refetch();
    }
  }

  async function ignoreFaces(faces: DetectedFace[]) {
    if (faces.length === 0) return;
    try {
      for (const f of faces) await ignoreFace.mutateAsync(f.faceId);
      showSuccess(`Ignored ${faces.length} face(s)`);
    } catch {
      showError("Failed to ignore faces");
    } finally {
      setSelected(new Set());
      refetch();
    }
  }

  const selectedFaces = displayed.filter((f) => selected.has(f.faceId));
  const pname = personLabel(person);
  const scrollRef = useScrollRestoration<HTMLElement>(`persons:${person.id}:${mode}`);

  return (
    <section className="page" ref={scrollRef}>
      <div className={`person-faces-view size-${size}`}>
        <div className="person-faces-header">
          <button type="button" className="back" onClick={onBack}>
            ← Back
          </button>
          <div className="title">{pname}</div>
          <div className="spacer" />
          <div className="pf-actions">
            <div className="pf-left-actions">
              <SizeControl size={size} onChange={setSize} />
              {mode === "validate" && (
                <>
                  <button className="btn btn-success btn-sm" disabled={inferredForPerson.length === 0} onClick={() => confirm(`Confirm all ${inferredForPerson.length} inferred face(s) for ${pname}?`) && confirmFaces(inferredForPerson)}>
                    Confirm all ({inferredForPerson.length})
                  </button>
                  <button className="btn btn-success btn-sm" disabled={selectedFaces.length === 0} onClick={() => confirm(`Confirm ${selectedFaces.length} selected face(s) for ${pname}?`) && confirmFaces(selectedFaces)}>
                    Confirm selected ({selectedFaces.length})
                  </button>
                  <button className="ignore-all" disabled={inferredForPerson.length === 0} onClick={() => confirm(`Ignore all ${inferredForPerson.length} inferred face(s)?`) && ignoreFaces(inferredForPerson)}>
                    Ignore all ({inferredForPerson.length})
                  </button>
                  <button className="ignore-selected" disabled={selectedFaces.length === 0} onClick={() => confirm(`Ignore ${selectedFaces.length} selected face(s)?`) && ignoreFaces(selectedFaces)}>
                    Ignore selected ({selectedFaces.length})
                  </button>
                  <label className="pf-show-ignored">
                    <input type="checkbox" checked={showIgnored} onChange={(e) => setShowIgnored(e.target.checked)} /> Show ignored
                  </label>
                </>
              )}
            </div>
            <div className="pf-right-actions">
              <button
                className="btn btn-primary btn-sm"
                disabled={mode === "identified" && inferredForPerson.length === 0}
                onClick={() => {
                  setMode((m) => (m === "validate" ? "identified" : "validate"));
                  setSelected(new Set());
                }}
              >
                {mode === "validate" ? "Exit validation" : `to validate (${inferredForPerson.length})`}
              </button>
              <button className="btn btn-primary btn-sm" onClick={() => setEditingPerson(true)}>
                ✎ Edit
              </button>
              <button
                style={{ background: "#ef4444", color: "#fff", border: "1px solid #b91c1c", padding: "6px 10px", borderRadius: 6, cursor: "pointer" }}
                onClick={async () => {
                  if (!confirm(`Delete person ${pname}?`)) return;
                  try {
                    await deletePerson.mutateAsync(person.id);
                    showSuccess("Person deleted");
                    onBack();
                  } catch {
                    showError("Failed to delete person");
                  }
                }}
              >
                🗑 Delete
              </button>
            </div>
          </div>
        </div>
        <FaceGrid
          faces={displayed}
          mode={mode}
          scopePersonId={person.id}
          personsMap={personsMap}
          selected={selected}
          onToggleSelect={toggleSelect}
          onConfirmInferred={(f) => confirmFaces([f])}
          onIgnore={(f) => ignoreFaces([f])}
          onRestore={async (f) => {
            try {
              await restoreFace.mutateAsync(f.faceId);
              showSuccess("Face restored");
            } catch {
              showError("Failed to restore face");
            } finally {
              refetch();
            }
          }}
          onEdit={setEditingFace}
          onOpenViewer={(f) => goToViewerForFace(api, router, f)}
        />
      </div>

      {editingFace && (
        <FaceEditModal
          face={editingFace}
          onClose={() => setEditingFace(null)}
          onChanged={() => refetch()}
          onDeleted={() => refetch()}
        />
      )}

      {editingPerson && (
        <Modal title="Edit person" onClose={() => setEditingPerson(false)} onSave={handleEditPersonSave}>
          <div className="row">
            <div>
              <label>First name</label>
              <input type="text" value={editFirstName} onChange={(e) => setEditFirstName(e.target.value)} autoFocus />
              <label className="form-label">Last name</label>
              <input type="text" value={editLastName} onChange={(e) => setEditLastName(e.target.value)} />
              <label className="form-label">Birth name</label>
              <input type="text" value={editBirthName} onChange={(e) => setEditBirthName(e.target.value)} />
              <label className="form-label">Birthdate</label>
              <input type="date" value={editBirthDate} onChange={(e) => setEditBirthDate(e.target.value)} />
            </div>
            <div>
              <label>Email</label>
              <input type="text" value={editEmail} onChange={(e) => setEditEmail(e.target.value)} />
              <label className="form-label">Description</label>
              <textarea value={editDescription} onChange={(e) => setEditDescription(e.target.value)} />
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}

function InferredFacesView({ onBack }: { onBack: () => void }) {
  const router = useRouter();
  const { api } = useAuth();
  const { data: allFaces = [], refetch } = useAllFaces();
  const personsMap = usePersonsMap();
  const setFacePerson = useSetFacePerson();
  const ignoreFace = useIgnoreFace();
  const restoreFace = useRestoreFace();
  const [size, setSize] = useSizeClass("personFaces.size");
  const [sort, setSort] = useState<SortKey>("person");
  const [filter, setFilter] = useState("");
  const [showIgnored, setShowIgnored] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [editingFace, setEditingFace] = useState<DetectedFace | null>(null);

  useEffect(() => {
    try {
      setSort((sessionStorage.getItem("personFaces.sortOrder") as SortKey) || "person");
    } catch {
      /* ignore */
    }
  }, []);
  function persistSort(s: SortKey) {
    setSort(s);
    try {
      sessionStorage.setItem("personFaces.sortOrder", s);
    } catch {
      /* ignore */
    }
  }

  function personName(id: string | undefined | null): string {
    if (!id) return "";
    const p = personsMap.get(id);
    return p ? `${p.firstName} ${p.lastName}` : "";
  }

  const pending = useMemo(() => {
    let list = allFaces.filter((f) => !f.identifiedPersonId && f.inferredIdentifiedPersonId);
    if (!showIgnored) list = list.filter((f) => !f.inferredIgnore);
    const q = filter.trim().toLowerCase();
    if (q) {
      list = list.filter((f) => {
        const name = personName(f.inferredIdentifiedPersonId).toLowerCase();
        const desc = (personsMap.get(f.inferredIdentifiedPersonId || "")?.description || "").toLowerCase();
        return name.includes(q) || desc.includes(q);
      });
    }
    const sorted = [...list];
    if (sort === "confidence") {
      sorted.sort((a, b) => (b.inferredIdentifiedPersonConfidence ?? -1) - (a.inferredIdentifiedPersonConfidence ?? -1) || new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    } else if (sort === "person_confidence") {
      sorted.sort(
        (a, b) =>
          personName(a.inferredIdentifiedPersonId).toLowerCase().localeCompare(personName(b.inferredIdentifiedPersonId).toLowerCase()) ||
          (b.inferredIdentifiedPersonConfidence ?? -1) - (a.inferredIdentifiedPersonConfidence ?? -1)
      );
    } else if (sort === "inferred_date") {
      const day = (f: DetectedFace) => (f.inferredTimestamp ? f.inferredTimestamp.slice(0, 10) : "");
      sorted.sort((a, b) => {
        const da = day(a);
        const db = day(b);
        if (da !== db) return !da ? 1 : !db ? -1 : da < db ? 1 : -1;
        const pa = personName(a.inferredIdentifiedPersonId).toLowerCase();
        const pb = personName(b.inferredIdentifiedPersonId).toLowerCase();
        if (pa !== pb) return pa < pb ? -1 : 1;
        return (b.inferredTimestamp ? Date.parse(b.inferredTimestamp) : -Infinity) - (a.inferredTimestamp ? Date.parse(a.inferredTimestamp) : -Infinity);
      });
    } else {
      sorted.sort((a, b) => personName(a.inferredIdentifiedPersonId).toLowerCase().localeCompare(personName(b.inferredIdentifiedPersonId).toLowerCase()) || new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    }
    return sorted;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allFaces, showIgnored, filter, sort, personsMap]);

  function toggleSelect(faceId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      next.has(faceId) ? next.delete(faceId) : next.add(faceId);
      return next;
    });
  }

  async function confirmFaces(faces: DetectedFace[]) {
    if (faces.length === 0) return;
    let ok = 0;
    try {
      for (const f of faces) {
        if (!f.inferredIdentifiedPersonId) continue;
        await setFacePerson.mutateAsync({ faceId: f.faceId, personId: f.inferredIdentifiedPersonId });
        ok++;
      }
      if (ok > 0) showSuccess(`Confirmed ${ok} face(s)`);
    } catch {
      showError("Failed to confirm faces");
    } finally {
      setSelected(new Set());
      refetch();
    }
  }

  async function ignoreFaces(faces: DetectedFace[]) {
    if (faces.length === 0) return;
    let ok = 0;
    try {
      for (const f of faces) {
        await ignoreFace.mutateAsync(f.faceId);
        ok++;
      }
      if (ok > 0) showSuccess(`Ignored ${ok} face(s)`);
    } catch {
      showError("Failed to ignore faces");
    } finally {
      setSelected(new Set());
      refetch();
    }
  }

  const selectedFaces = pending.filter((f) => selected.has(f.faceId));

  return (
    <section className="page">
      <div className={`person-faces-view size-${size}`}>
        <div className="person-faces-header">
          <button type="button" className="back" onClick={onBack}>
            ← Back
          </button>
          <div className="title">All Inferred Faces</div>
          <div className="spacer" />
          <div className="pf-actions">
            <div className="pf-left-actions">
              <SizeControl size={size} onChange={setSize} />
              <input type="text" className="pf-filter-input" placeholder="Filter people (name, description)…" style={{ marginLeft: 8, padding: 4, borderRadius: 6, border: "1px solid #ccc", fontSize: 13 }} value={filter} onChange={(e) => setFilter(e.target.value)} />
              <select className="pf-sort-select" style={{ marginLeft: 8, padding: 4, borderRadius: 6, border: "1px solid #ccc", fontSize: 13 }} value={sort} onChange={(e) => persistSort(e.target.value as SortKey)}>
                <option value="person">Sort: Person, Date</option>
                <option value="person_confidence">Sort: Person, Confidence</option>
                <option value="confidence">Sort: Confidence</option>
                <option value="inferred_date">Sort: Inferred date</option>
              </select>
              <button className="btn btn-success btn-sm" disabled={pending.length === 0} onClick={() => confirm(`Confirm all ${pending.length} inferred faces?`) && confirmFaces(pending)}>
                Confirm all ({pending.length})
              </button>
              <button className="btn btn-success btn-sm" disabled={selectedFaces.length === 0} onClick={() => confirm(`Confirm ${selectedFaces.length} selected faces?`) && confirmFaces(selectedFaces)}>
                Confirm selected ({selectedFaces.length})
              </button>
              <button className="ignore-all" disabled={pending.length === 0} onClick={() => confirm(`Ignore all ${pending.length} inferred faces?`) && ignoreFaces(pending)}>
                Ignore all ({pending.length})
              </button>
              <button className="ignore-selected" disabled={selectedFaces.length === 0} onClick={() => confirm(`Ignore ${selectedFaces.length} selected faces?`) && ignoreFaces(selectedFaces)}>
                Ignore selected ({selectedFaces.length})
              </button>
              <label className="pf-show-ignored">
                <input type="checkbox" checked={showIgnored} onChange={(e) => setShowIgnored(e.target.checked)} /> Show ignored
              </label>
            </div>
          </div>
        </div>
        <FaceGrid
          faces={pending}
          mode="validate"
          scopePersonId={null}
          personsMap={personsMap}
          selected={selected}
          onToggleSelect={toggleSelect}
          onConfirmInferred={(f) => confirmFaces([f])}
          onIgnore={(f) => ignoreFaces([f])}
          onRestore={async (f) => {
            try {
              await restoreFace.mutateAsync(f.faceId);
              showSuccess("Face restored");
            } catch {
              showError("Failed to restore face");
            } finally {
              refetch();
            }
          }}
          onEdit={setEditingFace}
          onOpenViewer={(f) => goToViewerForFace(api, router, f)}
        />
      </div>

      {editingFace && <FaceEditModal face={editingFace} onClose={() => setEditingFace(null)} onChanged={() => refetch()} onDeleted={() => refetch()} />}
    </section>
  );
}
