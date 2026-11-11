# Third-party notices

SoapCLI is built on the work of others. This file lists what ships inside the jar and under which license.

## TailAdmin (web UI design and components)

The web UI is based on the design, the theme and the components of the free [TailAdmin React template](https://github.com/TailAdmin/free-react-tailwind-admin-dashboard) (version 2.5.0). Files derived from it are in `web-ui/src/components/ui`, `web-ui/src/icons`, `web-ui/src/utils` and `web-ui/src/index.css`.

```text
MIT License

Copyright (c) 2023 TailAdmin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Outfit font

The UI uses the Outfit typeface, bundled from the `@fontsource-variable/outfit` package (SIL Open Font License 1.1), so no font is downloaded from the internet.

```text
Copyright 2021 The Outfit Project Authors (https://github.com/Outfitio/Outfit-Fonts)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## JavaScript packages in the web UI bundle

These are the production dependencies of `web-ui` (React, Base UI, CodeMirror and their dependencies). Each is distributed under the license shown; their copyright notices are in their own repositories.

| Package | Version | License |
|---|---|---|
| @babel/runtime | 7.29.13 | MIT |
| @babel/runtime | 8.1.0 | MIT |
| @base-ui/react | 1.9.0 | MIT |
| @base-ui/utils | 0.5.0 | MIT |
| @codemirror/autocomplete | 6.20.4 | MIT |
| @codemirror/commands | 6.11.1 | MIT |
| @codemirror/lang-xml | 6.1.0 | MIT |
| @codemirror/language | 6.13.1 | MIT |
| @codemirror/lint | 6.9.7 | MIT |
| @codemirror/search | 6.7.2 | MIT |
| @codemirror/state | 6.7.6 | MIT |
| @codemirror/streamparser | 6.0.0 | MIT |
| @codemirror/theme-one-dark | 6.1.3 | MIT |
| @codemirror/view | 6.43.15 | MIT |
| @floating-ui/core | 1.8.0 | MIT |
| @floating-ui/dom | 1.8.0 | MIT |
| @floating-ui/react-dom | 2.1.9 | MIT |
| @floating-ui/utils | 0.2.12 | MIT |
| @fontsource-variable/outfit | 5.3.0 | OFL-1.1 |
| @lezer/common | 1.5.5 | MIT |
| @lezer/highlight | 1.2.5 | MIT |
| @lezer/lr | 1.4.12 | MIT |
| @lezer/xml | 1.0.6 | MIT |
| @marijn/find-cluster-break | 1.0.4 | MIT |
| @types/react | 19.3.0 | MIT |
| @uiw/codemirror-extensions-basic-setup | 4.25.12 | MIT |
| @uiw/codemirror-theme-github | 4.25.12 | MIT |
| @uiw/codemirror-themes | 4.25.12 | MIT |
| @uiw/react-codemirror | 4.25.12 | MIT |
| class-variance-authority | 0.7.1 | Apache-2.0 |
| clsx | 2.1.1 | MIT |
| codemirror | 6.0.2 | MIT |
| crelt | 1.0.7 | MIT |
| csstype | 3.2.3 | MIT |
| react | 19.3.1 | MIT |
| react-dom | 19.3.1 | MIT |
| reselect | 5.3.0 | MIT |
| scheduler | 0.28.0 | MIT |
| style-mod | 4.1.4 | MIT |
| tailwind-merge | 3.7.0 | MIT |
| use-sync-external-store | 1.7.0 | MIT |
| w3c-keyname | 2.2.8 | MIT |

The build tools (Vite, Tailwind CSS, TypeScript, Vitest...) produce the bundle but are not part of it.

## Java dependencies

The jar also contains [Jackson](https://github.com/FasterXML/jackson) (Apache License 2.0), used to read profile files and to speak JSON in the web API.

