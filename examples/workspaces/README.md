# SoapCLI with workspaces: a complete example you can edit

The SoapCLI web UI with **workspaces**: one folder per team or project, each with its own profiles, placeholder options,
attachments, saved requests and secrets, kept on the server so that everyone who opens the UI shares them. A small mock
service is included, so that you can try it all before pointing it at a real one.

Copy this folder wherever you keep such things, and change the files to suit you. There is nothing to create.

## Run it

```bash
cd examples/workspaces
docker compose up -d
```

Open <http://localhost:8080>. Stop it with `docker compose down`: your files stay where they are, because everything lives
in `workspaces/` and not in a Docker volume.

> **Linux:** the `user: "1000:1000"` line of `docker-compose.yml` is so that the UI can write to `./workspaces`. If your user is
> not 1000, put yours there (`id -u`, `id -g`). If it is wrong, everything still works, but the UI says that it cannot save or
> edit because the files are read-only. On Docker Desktop (macOS, Windows) it usually needs no change.

## What is in it

```text
examples/workspaces/
├── docker-compose.yml          the SoapCLI UI and a mock service to try it against
├── soapcli.env                 the container's environment: login and allowed destinations
├── workspaces/                 becomes /workspaces: each folder in it is a workspace
│   ├── billing/                a workspace you can use and change
│   │   ├── workspace.json      its description (and, if you want them, readOnly and allowedHosts)
│   │   ├── profiles.json       its profiles: a shared base, a demo that works, two examples for real services
│   │   ├── placeholders.json   the options of its placeholders: they become dropdowns in the UI
│   │   ├── secrets.env         the values the profiles refer to as ${NAME} (placeholders: use your own)
│   │   ├── templates/          the request files its profiles refer to (`requestFile`)
│   │   ├── attachments/        the files the UI can attach, by path (and the only ones: nothing outside this folder)
│   │   ├── requests/           the saved requests, one JSON file each (two to start with)
│   │   └── certs/              where keystores and truststores go (never committed; see the README there)
│   └── shipping/               a read-only workspace: it can be used, but not changed from the UI
└── mock/server.py              the mock service: it answers with what it received
```

A relative path in a profile starts in **its workspace's folder**, so `templates/order.xml` in the `billing` profiles means
`workspaces/billing/templates/order.xml`, and the whole workspace can be copied or moved as one.

## Try it

1. **Pick a workspace.** The switcher in the sidebar lists `billing` and `shipping`. Every address of the UI names the workspace
   (`#/request?w=billing`), so you can paste it to a colleague.
2. **A saved request.** Open the **Library**: *Get order (demo)* and *Upload an invoice (demo)* are saved in the workspace, grouped by
   folder. Press *Open* on the first, then *Send*: the answer is what the mock service received, with the `X-Client` header of the
   `base` profile and the variables you chose. The link button copies an address that opens that request for anyone.
3. **Save your own.** Change the request, press *Save*, choose **Workspace: billing**, give it a name and a folder. It is now a file in
   `workspaces/billing/requests/`, visible to everybody who opens the workspace. Change it and *Update*, or *Save as a new request*.
4. **An attachment.** Open *Upload an invoice (demo)* and send it: the server loads `attachments/invoice-1001.pdf` of the workspace,
   and the mock shows the Base64 it received as `[base64: … characters]`.
5. **Secrets.** Edit the `acceptance` profile: its `authBasic` is `${ACC_CREDENTIALS}`, which comes from `workspaces/billing/secrets.env`.
   The UI never shows it.
6. **A read-only workspace.** Switch to `shipping`: you can send through its profile, but *Save* offers only *This browser*, and the
   profiles cannot be edited, because its `workspace.json` says `"readOnly": true`.
7. **A new workspace.** On the **Workspaces** page press *New workspace*: a folder appears in `workspaces/`. A folder you make by
   hand shows up there too, without restarting.

From a terminal the same workspace works with the command line client (see the
[documentation](https://marcemv90.github.io/SoapCLI/workspaces/#from-the-command-line)):

```bash
docker run --rm -e SOAPCLI_WORKSPACES_DIR=/workspaces -v "$PWD/workspaces:/workspaces:ro" --network soapcli-workspaces-example_default \
  ghcr.io/marcemv90/soapcli --workspace billing --profile demo
```

(the request comes from the profile's `requestFile`, and `--network` lets it reach the `mock` service).

## Make it yours

- **Profiles, placeholders, secrets.** Put your endpoints in `workspaces/<name>/profiles.json` (by hand, or in the UI), the secrets in
  `secrets.env` next to it. Delete the `demo` profiles and the `mock` service when you point SoapCLI at your own services.
- **Workspaces.** Delete `billing` and `shipping` and make one per team or project. `workspace.json` can limit where a workspace may
  send (`allowedHosts`) and protect it (`readOnly`).
- **A version.** The compose file uses the `latest` image; write `ghcr.io/marcemv90/soapcli:<version>` to pin it.

## Sharing it with a team

As it is, the UI is published on `127.0.0.1`: only this machine can open it. Before you publish it on a network (`"8080:8080"`):

- set a login in `soapcli.env` (`SOAPCLI_UI_USER`, `SOAPCLI_UI_PASSWORD`; it is HTTP Basic, so put it behind **HTTPS**),
- restrict where it may call (`SOAPCLI_ALLOWED_HOSTS`),
- remember that **everybody who can sign in sees every workspace**, and that what is saved in one is readable by all of them,
- think about whether the UI should be allowed to edit profiles: someone who can edit a profile can point it at another destination,
  and so receive its credentials. Delete `SOAPCLI_ALLOW_CONFIG_EDIT` if not (saving requests keeps working).

The start-up log shows a warning about that ("the UI may change the profiles file…"): it appears because the server, inside the
container, listens on a network address with editing on. The port is only published on this machine; the warning is there for
the day it is not.

More in the documentation: [Workspaces](https://marcemv90.github.io/SoapCLI/workspaces/), [Docker](https://marcemv90.github.io/SoapCLI/docker/)
and [Web UI](https://marcemv90.github.io/SoapCLI/web-ui/).
