import { useMemo, useState, type ReactNode } from "react";
import { FormSection, SecretField, SelectField, TableEditor, TextField, TriField } from "@/components/profiles/form-controls";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { AlertIcon } from "@/icons";
import {
  MASK, PROFILE_NAME, THEMES, clone, getAt, isDirty, isNullAt, rowsAt, setAt, setNumberOrText, setText, setTri, tableOf, textAt, triAt,
  unknownKeys, type Definition, type Path, type Row,
} from "@/lib/profile-form";
import { ServerError, saveProfile, type SaveResult, type ServerProfile } from "@/lib/transports/server";

interface Props {
  mode: "create" | "edit";
  /** The profile being edited; empty when creating. */
  name: string;
  /** What the file says (secrets as `****`), or nothing for a new profile. */
  initial: Definition;
  /** The revision of the file `initial` was read from. */
  revision: string;
  /** The profiles that inherit from this one. */
  dependents: string[];
  /** Every profile, to choose what to inherit from and to show what is inherited. */
  profiles: ServerProfile[];
  /** Called once the profile is saved; the form stays "saving" until the page that shows it takes it away. */
  onSaved: (name: string, result: SaveResult) => void | Promise<void>;
  onCancel: () => void;
  /** The file changed under the form: load it again (the edits in the form are given up). */
  onReload: () => void;
}

const SOAP_VERSIONS = [{ value: "", label: "Not set" }, { value: "auto", label: "Auto-detect" }, { value: "1.1", label: "SOAP 1.1" }, { value: "1.2", label: "SOAP 1.2" }];
const STORE_TYPES = [{ value: "", label: "Not set (PKCS12)" }, { value: "PKCS12", label: "PKCS12" }, { value: "JKS", label: "JKS" }];
const PROXY_TYPES = [{ value: "", label: "Not set (http)" }, { value: "http", label: "HTTP" }, { value: "socks", label: "SOCKS" }];

/** Edits one profile as a form. What the form does not know about the profile is kept as it is. */
export function ProfileForm({ mode, name: existingName, initial, revision, dependents, profiles, onSaved, onCancel, onReload }: Props) {
  const [name, setName] = useState(existingName);
  const [def, setDef] = useState<Definition>(() => clone(initial));
  const [headers, setHeaders] = useState<Row[]>(() => rowsAt(initial, "headers"));
  const [args, setArgs] = useState<Row[]>(() => rowsAt(initial, "args"));
  const [attachments, setAttachments] = useState<Row[]>(() => rowsAt(initial, "attachments"));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<{ message: string; conflict: boolean } | null>(null);

  const parentName = typeof def.extends === "string" ? def.extends : "";
  const parent = useMemo(() => profiles.find((p) => p.name === parentName)?.settings as Definition | undefined, [profiles, parentName]);
  /** What the profile would get from the one it extends, to show where a field is left empty. */
  const inherited = (path: Path): string | undefined => {
    const v = parent ? getAt(parent, path) : undefined;
    return v === undefined || v === null ? undefined : v === MASK ? "(saved)" : String(v);
  };
  const hint = (path: Path, fallback?: string) => {
    const v = inherited(path);
    return v !== undefined ? `inherited: ${v}` : fallback;
  };

  const text = (path: Path) => textAt(def, path);
  const set = (path: Path) => (value: string) => setDef((d) => setText(d, path, value));
  const setNum = (path: Path) => (value: string) => setDef((d) => setNumberOrText(d, path, value));
  const hadSaved = (path: Path) => getAt(initial, path) === MASK;
  const setTable = (key: string, setRows: (rows: Row[]) => void) => (rows: Row[]) => {
    setRows(rows);
    setDef((d) => setAt(d, [key], tableOf(rows)));
  };

  const dirty = isDirty(def, initial);
  const nameOk = mode === "edit" || PROFILE_NAME.test(name);
  const canSave = !saving && nameOk && (mode === "create" || dirty);
  const unknown = unknownKeys(def);
  const parents = profiles.map((p) => p.name).filter((n) => n !== existingName && !dependents.includes(n));

  const save = async () => {
    setSaving(true);
    setError(null);
    let result: SaveResult;
    try {
      result = await saveProfile({ name: name.trim(), revision, create: mode === "create", definition: def });
    } catch (e) {
      setError({ message: e instanceof Error ? e.message : String(e), conflict: e instanceof ServerError && e.category === "conflict" });
      setSaving(false);
      return;
    }
    await onSaved(name.trim(), result);
    setSaving(false);
  };

  /** A group (proxy, certificate...) can be switched off for a profile that inherits it. */
  const groupSwitch = (group: string, label: string): ReactNode => {
    const off = isNullAt(def, [group]);
    if (!parentName || (!off && getAt(parent ?? {}, [group]) === undefined)) return null;
    return (
      <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
        <input type="checkbox" checked={off} className="size-4 rounded border-gray-300 text-brand-500 focus:ring-brand-500/30 dark:border-gray-600 dark:bg-gray-900"
          onChange={(e) => setDef((d) => setAt(d, [group], e.target.checked ? null : undefined))} />
        Do not use the {label} that {parentName} defines
      </label>
    );
  };
  const grid = "grid gap-4 sm:grid-cols-2";

  return (
    <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); if (canSave) void save(); }} aria-label={mode === "create" ? "New profile" : `Edit ${existingName}`}>
      {error && (
        <Alert variant="destructive">
          <AlertIcon />
          <AlertTitle>{error.conflict ? "Cannot save" : "The profile was not saved"}</AlertTitle>
          <AlertDescription>
            {error.message}
            {error.conflict && /changed since you opened it/.test(error.message) && (
              <span className="mt-2 block"><Button type="button" variant="outline" size="xs" onClick={onReload}>Reload the file (gives up my edits)</Button></span>
            )}
          </AlertDescription>
        </Alert>
      )}
      {dependents.length > 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          These profiles inherit from this one, so a change here reaches them: {dependents.join(", ")}.
        </p>
      )}

      <FormSection title="Profile">
        <div className={grid}>
          {mode === "create" ? (
            <TextField label="Name" value={name} onChange={setName} mono
              hint={name && !PROFILE_NAME.test(name) ? "Letters, digits, . _ - (at most 64)" : "What you give to --profile; letters, digits, . _ -"} />
          ) : (
            <div><span className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Name</span><span className="font-mono text-sm text-gray-800 dark:text-white/90">{existingName}</span></div>
          )}
          <SelectField label="Inherits from" value={parentName} hint="Starts from that profile and changes only what is set here."
            onChange={(v) => setDef((d) => setText(d, ["extends"], v))}
            options={[{ value: "", label: "Nothing" }, ...parents.map((n) => ({ value: n, label: n }))]} />
        </div>
      </FormSection>

      <FormSection title="Request">
        <div className={grid}>
          <TextField label="Endpoint" value={text(["endpoint"])} onChange={set(["endpoint"])} placeholder={hint(["endpoint"], "https://host/service")} mono />
          <TextField label="Request file" value={text(["requestFile"])} onChange={set(["requestFile"])} mono placeholder={hint(["requestFile"])}
            hint="A file on the server that fills the editor." />
          <SelectField label="SOAP version" value={text(["soapVersion"])} onChange={set(["soapVersion"])} options={SOAP_VERSIONS}
            hint={hint(["soapVersion"])} />
          <TextField label="SOAP action" value={text(["soapAction"])} onChange={set(["soapAction"])} placeholder={hint(["soapAction"])} mono />
          <TriField label="Send attachments as MTOM" tri={triAt(def, ["mtom"])} onChange={(c) => setDef((d) => setTri(d, ["mtom"], c))} inherited={inherited(["mtom"])} />
        </div>
      </FormSection>

      <FormSection title="Connection">
        <div className={grid}>
          <TextField label="Timeout (seconds)" value={text(["timeout"])} onChange={setNum(["timeout"])} placeholder={hint(["timeout"], "60")}
            hint="0 waits forever. A ${VAR} is accepted." />
          <TextField label="Connect timeout (seconds)" value={text(["connectTimeout"])} onChange={setNum(["connectTimeout"])} placeholder={hint(["connectTimeout"])} />
          <TriField label="Follow redirects" tri={triAt(def, ["followRedirects"])} onChange={(c) => setDef((d) => setTri(d, ["followRedirects"], c))} inherited={inherited(["followRedirects"])} />
          <TriField label="Skip TLS certificate checks" tri={triAt(def, ["insecure"])} onChange={(c) => setDef((d) => setTri(d, ["insecure"], c))} inherited={inherited(["insecure"])}
            hint="For throw-away tests only." />
        </div>
      </FormSection>

      <FormSection title="Headers" description="Sent with every request made with this profile.">
        <TableEditor rows={headers} onChange={setTable("headers", setHeaders)} nameLabel="Header name" valueLabel="Header value"
          namePlaceholder="X-Header" valuePlaceholder="value" addLabel="Add header" emptyText="No headers of its own." />
      </FormSection>

      <FormSection title="Variables" description="Values for the ___NAME:default___ placeholders of a request. An empty value is a value too.">
        <TableEditor rows={args} onChange={setTable("args", setArgs)} nameLabel="Variable name" valueLabel="Variable value" mono
          namePlaceholder="ORDER_ID" valuePlaceholder="value" addLabel="Add variable" emptyText="No values of its own." />
      </FormSection>

      <FormSection title="Attachments" description="Files on the server for the cid: references of a request.">
        <TableEditor rows={attachments} onChange={setTable("attachments", setAttachments)} nameLabel="CID" valueLabel="File path" mono
          namePlaceholder="cid" valuePlaceholder="~/files/payload.xml" addLabel="Add attachment" emptyText="No attachments of its own." />
      </FormSection>

      <FormSection title="Authentication" description="Secrets are never sent to this page: a saved one shows as “saved”.">
        <div className={grid}>
          <SecretField label="Basic credentials (user:password)" value={text(["authBasic"])} onChange={set(["authBasic"])} hadSaved={hadSaved(["authBasic"])}
            hint={inherited(["authBasic"]) ? `inherited: ${inherited(["authBasic"])}` : "Or ${VAR} to read it from the server's environment."} />
          <SecretField label="Bearer token" value={text(["authBearer"])} onChange={set(["authBearer"])} hadSaved={hadSaved(["authBearer"])}
            hint={inherited(["authBearer"]) ? `inherited: ${inherited(["authBearer"])}` : undefined} />
        </div>
      </FormSection>

      <FormSection title="Client certificate" description="A keystore on the server, for services that ask for a certificate.">
        {groupSwitch("keystore", "client certificate")}
        {!isNullAt(def, ["keystore"]) && (
          <div className={grid}>
            <TextField label="Path" value={text(["keystore", "path"])} onChange={set(["keystore", "path"])} mono placeholder={hint(["keystore", "path"])} />
            <SelectField label="Type" value={text(["keystore", "type"])} onChange={set(["keystore", "type"])} options={STORE_TYPES} />
            <TextField label="Alias" value={text(["keystore", "alias"])} onChange={set(["keystore", "alias"])} placeholder={hint(["keystore", "alias"])} />
            <SecretField label="Keystore password" value={text(["keystore", "password"])} onChange={set(["keystore", "password"])} hadSaved={hadSaved(["keystore", "password"])} />
            <SecretField label="Key password" value={text(["keystore", "aliasPkPass"])} onChange={set(["keystore", "aliasPkPass"])} hadSaved={hadSaved(["keystore", "aliasPkPass"])} />
          </div>
        )}
      </FormSection>

      <FormSection title="Trust store" description="The certificates to trust, instead of the Java defaults.">
        {groupSwitch("truststore", "trust store")}
        {!isNullAt(def, ["truststore"]) && (
          <div className={grid}>
            <TextField label="Path" value={text(["truststore", "path"])} onChange={set(["truststore", "path"])} mono placeholder={hint(["truststore", "path"])} />
            <SelectField label="Type" value={text(["truststore", "type"])} onChange={set(["truststore", "type"])} options={STORE_TYPES} />
            <SecretField label="Trust store password" value={text(["truststore", "password"])} onChange={set(["truststore", "password"])} hadSaved={hadSaved(["truststore", "password"])} />
          </div>
        )}
      </FormSection>

      <FormSection title="Proxy">
        {groupSwitch("proxy", "proxy")}
        {!isNullAt(def, ["proxy"]) && (
          <div className={grid}>
            <SelectField label="Type" value={text(["proxy", "type"])} onChange={set(["proxy", "type"])} options={PROXY_TYPES} />
            <TextField label="Host" value={text(["proxy", "host"])} onChange={set(["proxy", "host"])} mono placeholder={hint(["proxy", "host"])} />
            <TextField label="Port" value={text(["proxy", "port"])} onChange={setNum(["proxy", "port"])} placeholder={hint(["proxy", "port"])} />
            <TextField label="User" value={text(["proxy", "user"])} onChange={set(["proxy", "user"])} placeholder={hint(["proxy", "user"])} />
            <SecretField label="Proxy password" value={text(["proxy", "password"])} onChange={set(["proxy", "password"])} hadSaved={hadSaved(["proxy", "password"])} />
          </div>
        )}
      </FormSection>

      <FormSection title="Command line" description="Only used by the command line client.">
        <div className={grid}>
          <TextField label="Colour theme" value={text(["theme"])} onChange={set(["theme"])} placeholder={hint(["theme"], "darcula")} list="profile-themes" />
          <datalist id="profile-themes">{THEMES.map((t) => <option key={t} value={t} />)}</datalist>
        </div>
      </FormSection>

      {unknown.length > 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          This profile also has settings this form does not know, which are kept as they are:{" "}
          {unknown.map((k) => <Badge key={k} variant="light" color="gray" size="sm" className="me-1">{k}</Badge>)}
        </p>
      )}

      <div className="sticky bottom-0 -mx-4 -mb-4 flex flex-wrap items-center gap-3 border-t border-gray-200 bg-white/95 px-4 py-3 backdrop-blur sm:-mx-5 sm:-mb-5 sm:px-5 dark:border-gray-800 dark:bg-gray-900/95">
        <span className="text-xs text-gray-500 dark:text-gray-400" aria-live="polite">
          {mode === "create" ? "A new profile is added to the file." : dirty ? "Unsaved changes" : "No changes"}
        </span>
        <div className="ms-auto flex gap-2">
          <Button type="button" variant="outline" onClick={onCancel} disabled={saving}>Cancel</Button>
          <Button type="submit" disabled={!canSave}>{saving && <Spinner />} {mode === "create" ? "Create profile" : "Save changes"}</Button>
        </div>
      </div>
    </form>
  );
}
