import { useEffect, useRef, useState, type FormEvent } from "react";
import { useLocation } from "react-router-dom";
import { useAuth } from "../../features/auth/AuthContext";
import { sendChat } from "../../shared/api/client";
import type { ChatCitation } from "../../shared/api/types";

type Role = "user" | "assistant";

interface ChatMessage {
  id: string;
  role: Role;
  text: string;
  citations?: ChatCitation[];
  error?: boolean;
}

function newId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function todayIsoRange(): { from: string; to: string } {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
  return { from: start.toISOString(), to: end.toISOString() };
}

export function ChatPage() {
  const { token } = useAuth();
  const location = useLocation();
  const chatVisible = location.pathname === "/chat";
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [question, setQuestion] = useState("");
  const [sending, setSending] = useState(false);
  const [modelFilter, setModelFilter] = useState("");
  const [versionFilter, setVersionFilter] = useState("");
  const [dateFilter, setDateFilter] = useState<"all" | "today">("all");
  const listRef = useRef<HTMLDivElement>(null);
  const catchUpScroll = useRef(false);

  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    // A hidden list has no layout; assigning scrollTop would clamp it to 0.
    if (!chatVisible || el.getClientRects().length === 0) {
      catchUpScroll.current = true;
      return;
    }
    el.scrollTop = el.scrollHeight;
    catchUpScroll.current = false;
    // chatVisible is read from this render. Re-running on visibility would
    // jump to the bottom every time the user returns to Chat.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages]);

  useEffect(() => {
    if (!chatVisible || !catchUpScroll.current) return;
    const el = listRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    catchUpScroll.current = false;
  }, [chatVisible]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!token || !question.trim() || sending) return;

    const userMsg: ChatMessage = {
      id: newId(),
      role: "user",
      text: question.trim(),
    };
    setMessages((prev) => [...prev, userMsg]);
    setQuestion("");
    setSending(true);

    const range = dateFilter === "today" ? todayIsoRange() : null;
    const history = messages.map((m) => ({ role: m.role, text: m.text }));
    try {
      const res = await sendChat(token, {
        question: userMsg.text,
        modelName: modelFilter.trim() || undefined,
        version: versionFilter.trim() || undefined,
        fromIso: range?.from,
        toIso: range?.to,
        history: history.length > 0 ? history : undefined,
      });
      setMessages((prev) => [
        ...prev,
        {
          id: newId(),
          role: "assistant",
          text: res.answer,
          citations: res.citations,
        },
      ]);
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          id: newId(),
          role: "assistant",
          text: err instanceof Error ? err.message : "Chat failed",
          error: true,
        },
      ]);
    } finally {
      setSending(false);
    }
  }

  function clearConversation() {
    setMessages([]);
  }

  return (
    <section className="panel chat-panel">
      <div className="panel-header">
        <h1>Ask AMRAP</h1>
        <button type="button" className="btn ghost" onClick={clearConversation} disabled={sending}>
          Clear
        </button>
      </div>

      <div className="grid">
        <label>
          Model
          <input
            value={modelFilter}
            onChange={(e) => setModelFilter(e.target.value)}
            placeholder="e.g. M1"
          />
        </label>
        <label>
          Version
          <input
            value={versionFilter}
            onChange={(e) => setVersionFilter(e.target.value)}
            placeholder="e.g. 1.0.0"
          />
        </label>
        <label>
          Date
          <select value={dateFilter} onChange={(e) => setDateFilter(e.target.value as typeof dateFilter)}>
            <option value="all">All time</option>
            <option value="today">Today</option>
          </select>
        </label>
      </div>

      <div className="chat-list" ref={listRef}>
        {messages.length === 0 ? (
          <div className="chat-empty">
            <p className="muted">Try one of these to get started:</p>
            <ul>
              <li>"What results do we have from today?"</li>
              <li>"Summarize the latest run of model M1"</li>
              <li>"Which iteration had the highest confidence for M1?"</li>
            </ul>
          </div>
        ) : null}
        {messages.map((m) => (
          <ChatBubble key={m.id} message={m} />
        ))}
      </div>

      <form className="chat-input" onSubmit={onSubmit}>
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Ask a question about your results…"
          disabled={sending}
        />
        <button type="submit" className="btn primary" disabled={sending || !question.trim()}>
          {sending ? "Thinking…" : "Send"}
        </button>
      </form>
    </section>
  );
}

function ChatBubble({ message }: { message: ChatMessage }) {
  const bubbleClass = `chat-bubble ${message.role}${message.error ? " error" : ""}`;
  return (
    <div className={bubbleClass}>
      <div className="chat-bubble-text">{message.text}</div>
      {message.citations && message.citations.length > 0 ? (
        <details className="chat-citations">
          <summary>Sources ({message.citations.length})</summary>
          <ul>
            {message.citations.map((c, i) => (
              <li key={`${c.resultId ?? "x"}-${i}`}>
                <strong>{c.modelName ?? "?"}</strong> · v{c.version ?? "?"} · iter{" "}
                {c.iterationId ?? "?"}
                {typeof c.score === "number" ? (
                  <span className="muted"> (score {c.score.toFixed(3)})</span>
                ) : null}
                <br />
                <span className="muted">
                  result {c.resultId ?? "unknown"} · {formatDateTime(c.insertedAt)}
                </span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}
