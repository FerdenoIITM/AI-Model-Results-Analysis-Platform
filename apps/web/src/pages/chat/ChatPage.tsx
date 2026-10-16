import { useEffect, useRef, useState, type FormEvent } from "react";
import Markdown from "react-markdown";
import { useLocation } from "react-router-dom";
import remarkGfm from "remark-gfm";
import { useAuth } from "../../features/auth/AuthContext";
import { MultiSelectFilter } from "../../features/results/MultiSelectFilter";
import { fetchFilterOptions, sendChat } from "../../shared/api/client";
import type { FilterOptions } from "../../shared/api/types";

type Role = "user" | "assistant";

interface ChatMessage {
  id: string;
  role: Role;
  text: string;
  error?: boolean;
}

function newId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function withSelected(options: string[], selected: string[]): string[] {
  const seen = new Set(options);
  return [...options, ...selected.filter((item) => !seen.has(item))];
}

function isAbort(err: unknown): boolean {
  return err instanceof Error && err.name === "AbortError";
}

export function ChatPage() {
  const { token } = useAuth();
  const location = useLocation();
  const chatVisible = location.pathname === "/chat";
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [question, setQuestion] = useState("");
  const [sending, setSending] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [subCategories, setSubCategories] = useState<string[]>([]);
  const [options, setOptions] = useState<FilterOptions>({ models: [], categories: [], subCategories: [] });
  const listRef = useRef<HTMLDivElement>(null);
  const catchUpScroll = useRef(false);
  const categoriesRef = useRef(categories);
  categoriesRef.current = categories;
  const subCategoriesRef = useRef(subCategories);
  subCategoriesRef.current = subCategories;
  const categoryKey = categories.join("\u0000");

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

  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    fetchFilterOptions(token, categoriesRef.current, controller.signal)
      .then((opts) => {
        setOptions(opts);
        const allowed = new Set(opts.subCategories);
        const kept = subCategoriesRef.current.filter((item) => allowed.has(item));
        if (kept.length !== subCategoriesRef.current.length) {
          setSubCategories(kept);
        }
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        // Options stay empty; chat still works without scoped filters.
      });
    return () => controller.abort();
  }, [token, categoryKey]);

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

    const history = messages.map((m) => ({ role: m.role, text: m.text }));
    try {
      const res = await sendChat(token, {
        question: userMsg.text,
        modelNames: models.length > 0 ? models : undefined,
        categories: categories.length > 0 ? categories : undefined,
        subCategories: subCategories.length > 0 ? subCategories : undefined,
        history: history.length > 0 ? history : undefined,
      });
      setMessages((prev) => [
        ...prev,
        {
          id: newId(),
          role: "assistant",
          text: res.answer,
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
        <MultiSelectFilter
          label="Model"
          options={withSelected(options.models, models)}
          selected={models}
          onChange={setModels}
        />
        <MultiSelectFilter
          label="Category"
          options={withSelected(options.categories, categories)}
          selected={categories}
          onChange={setCategories}
        />
        <MultiSelectFilter
          label="Sub Category"
          options={withSelected(options.subCategories, subCategories)}
          selected={subCategories}
          onChange={setSubCategories}
        />
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
  const renderMarkdown = message.role === "assistant" && !message.error;
  return (
    <div className={bubbleClass}>
      {renderMarkdown ? (
        <div className="chat-bubble-text chat-md">
          <Markdown remarkPlugins={[remarkGfm]}>{message.text}</Markdown>
        </div>
      ) : (
        <div className="chat-bubble-text">{message.text}</div>
      )}
    </div>
  );
}
