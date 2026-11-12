# SoapCLI with Docker: a complete example you can edit

Everything the SoapCLI web UI can use, already wired in one `docker-compose.yml`: profiles, placeholder options, request
files, attachments, secrets, and a small mock service so that you can try it all before pointing it at a real one.

Copy this folder wherever you keep such things, and change the files to suit you. There is nothing to create.

## Run it

```bash
cd examples/docker
docker compose up -d
```

Open <http://localhost:8080>. Stop it with `docker compose down`: your files stay where they are, because everything lives
in the folders below and not in Docker volumes.

> **Linux:** the `user: "1000:1000"` line of `docker-compose.yml` is so that the UI can write to `./config`. If your user is
> not 1000, put yours there (`id -u`, `id -g`). If it is wrong, everything still works, but the UI says that it cannot edit
> the files because they are read-only. On Docker Desktop (macOS, Windows) it usually needs no change.

## What is in it

```text
examples/docker/
├── docker-compose.yml          the SoapCLI UI and a mock service to try it against
├── soapcli.env                 the container's environment: login, allowed destinations, the secrets the profiles use
├── config/                     becomes /config in the container; the UI can edit what is here
│   ├── profiles.json           the profiles: a shared base, a demo that works, two examples for real services
│   ├── placeholders.json       the options of the placeholders: they become dropdowns in the UI
│   └── certs/                  where keystores and truststores go (never committed; see the README there)
├── work/                       becomes /work, read-only: the working directory of the container
│   ├── requests/               request files that profiles refer to (`requestFile`)
│   │   ├── demo-order.xml      a request with three variables
│   │   └── demo-upload.xml     a request with an attachment
│   └── attachments/            files the UI can attach, by path: hello.txt, invoice-1001.pdf, logo.png
└── mock/
    └── server.py               the mock service: it answers with what it received
```

Paths inside the profiles are paths **in the container**: `./config` is `/config`, and `./work` is `/work`, which is also the
working directory, so `requests/demo-order.xml` in a profile means `./work/requests/demo-order.xml`.

## Try it

1. **A request through a profile.** On the *Profiles* page, open `demo` and press *Load its request file*. On the request
   page the three variables of the request (`ENV`, `ORDER_ID`, `COUNTRY`) are **dropdowns**: their options come from
   `config/placeholders.json`. Pick some, press *Send*. The answer is what the mock service received: the headers (the
   `X-Client` one comes from the `base` profile that `demo` extends), the size and a preview of the body, with your choices
   in it.
2. **An attachment.** Pick `demo-upload` and load its request file. The request refers to `cid:invoice-1001`, so the
   *Attachments* section lists it: type `attachments/invoice-1001.pdf` in its row (the server checks the path as you type, and
   saving the request keeps it) and press *Send*. A dialog loads the file, and the mock shows the Base64 it received as
   `[base64: … characters]`. (`payloadId="cid:invoice"` is an attribute, so it is not an attachment and is not listed.) Turn
   on *MTOM* to send the file as a MIME part instead. The `demo-upload` profile also maps `invoice-1001` to that file, so
   *Send* works with the row left empty too: the row says "No file yet" because the page does not look inside profiles, but
   the server reads the file all the same.
3. **Edit a profile.** On the *Profiles* page press *Edit* on `acceptance`: the form changes `config/profiles.json` in place,
   keeping its comments and the other profiles exactly as they were.
4. **Give a variable options.** On the *Placeholders* page add one, or press *Give it options* under a variable of a request.

## Make it yours

- **Profiles.** Put your endpoints in `config/profiles.json` (by hand, or in the UI). Delete `demo` and `demo-upload` when you
  no longer need them. The format is in the [profiles documentation](https://marcemv90.github.io/SoapCLI/cli-profiles/).
- **Secrets.** A profile writes `"authBasic": "${ACC_CREDENTIALS}"`, and the value comes from `soapcli.env`: the profile never
  holds the password. Keep a file with real secrets out of version control.
- **Client certificates.** Put the keystore in `config/certs/` and write its path in the profile
  (`/config/certs/client.p12`); see the README there.
- **Requests and attachments.** Put your request files in `work/requests/` and name them in a profile with `requestFile`. Put the
  files to attach in `work/attachments/`: the UI can attach any file under `work/` (that is the only folder the server reads
  attachments from), by its path.
- **The mock service.** It is only there so that the demo works. Delete the `mock` service of `docker-compose.yml` and the
  `demo` profiles when you point SoapCLI at your own services.
- **A version.** The compose file uses the `latest` image; write `ghcr.io/marcemv90/soapcli:2.2.0` (or another release) to pin it.
- **Editing from the UI.** `SOAPCLI_ALLOW_CONFIG_EDIT` in `docker-compose.yml` lets the UI change `config/`. If it should only
  read it, delete that line and add `:ro` to the `./config:/config` volume.

## Sharing it with a team

As it is, the UI is published on `127.0.0.1`: only this machine can open it. Before you publish it on a network (`"8080:8080"`):

- set a login in `soapcli.env` (`SOAPCLI_UI_USER`, `SOAPCLI_UI_PASSWORD`; it is HTTP Basic, so put it behind **HTTPS**),
- restrict where it may call (`SOAPCLI_ALLOWED_HOSTS`),
- think about whether the UI should be allowed to edit the profiles: someone who can edit a profile can point it at another
  destination, and so receive its credentials. Delete `SOAPCLI_ALLOW_CONFIG_EDIT` if not.

The start-up log shows a warning about that ("the UI may change the profiles file…"): it appears because the server, inside
the container, listens on a network address with editing on. The port is only published on this machine; the warning is
there for the day it is not.

More in the documentation: [Docker](https://marcemv90.github.io/SoapCLI/docker/) and [Web UI](https://marcemv90.github.io/SoapCLI/web-ui/).
