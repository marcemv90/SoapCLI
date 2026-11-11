import { xml } from "@codemirror/lang-xml";
import { githubDarkInit, githubLightInit } from "@uiw/codemirror-theme-github";
import CodeMirror, { EditorView } from "@uiw/react-codemirror";
import { useMemo } from "react";
import { useTheme } from "@/state/theme";

const MONO = 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace';
const transparent = { background: "transparent", gutterBackground: "transparent", gutterBorder: "transparent", fontFamily: MONO };
const lightTheme = githubLightInit({ settings: { ...transparent, lineHighlight: "#465fff0d" } });
const darkTheme = githubDarkInit({ settings: { ...transparent, lineHighlight: "#ffffff0d" } });
const sizing = EditorView.theme({ "&": { fontSize: "13px" }, ".cm-scroller": { lineHeight: "1.55" } });

interface Props {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  minHeight?: string;
  maxHeight?: string;
  placeholder?: string;
  ariaLabel?: string;
}

/** An XML editor (or, read-only, viewer) with syntax highlighting, line numbers and folding. */
export function CodeEditor({ value, onChange, readOnly = false, minHeight = "320px", maxHeight = "70vh", placeholder, ariaLabel }: Props) {
  const { resolved } = useTheme();
  const extensions = useMemo(() => [xml(), EditorView.lineWrapping, sizing,
    EditorView.contentAttributes.of({ "aria-label": ariaLabel ?? (readOnly ? "Response body" : "Request body") })], [ariaLabel, readOnly]);
  return (
    <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
      <CodeMirror
        value={value}
        onChange={onChange}
        readOnly={readOnly}
        editable={!readOnly}
        extensions={extensions}
        theme={resolved === "dark" ? darkTheme : lightTheme}
        minHeight={minHeight}
        maxHeight={maxHeight}
        placeholder={placeholder}
        basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: !readOnly, autocompletion: false,
          searchKeymap: true, indentOnInput: false, bracketMatching: true, closeBrackets: false }}
      />
    </div>
  );
}
