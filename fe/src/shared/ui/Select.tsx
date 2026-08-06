"use client";

import { useCallback, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { useDismissOnOutsidePointer } from "./useDismissOnOutsidePointer";

export interface SelectOption {
  readonly value: string;
  readonly label: string;
}

/** 드롭다운 화살표 아이콘. Select 기본 트리거와 커스텀 트리거에서 함께 쓴다. */
export function ChevronDownIcon({ className = "" }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className={`shrink-0 ${className}`}
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

interface SelectProps {
  options: readonly SelectOption[];
  /** 선택된 옵션 value. 없으면 placeholder 를 보여준다. */
  value: string | null;
  onChange: (value: string) => void;
  placeholder?: string;
  /** label 의 htmlFor 와 연결할 트리거 버튼 id. */
  id?: string;
  disabled?: boolean;
  className?: string;
  "data-testid"?: string;
  "aria-label"?: string;
  /** 트리거 내용을 직접 그린다(아이콘·칩 트리거 등). 지정하면 기본 라벨+화살표 대신 사용된다. */
  trigger?: (state: { open: boolean; selected: SelectOption | null }) => ReactNode;
  /** trigger 지정 시 트리거 버튼 스타일을 통째로 대체한다. */
  triggerClassName?: string;
  /** 목록이 열리는 방향. 기본은 아래(bottom). */
  placement?: "bottom" | "top";
  /** 목록의 폭·정렬을 커스터마이징한다 (기본은 트리거와 같은 폭). */
  listClassName?: string;
}

/**
 * ZANI 디자인 토큰을 따르는 커스텀 셀렉트.
 * 네이티브 select 의 OS 기본 드롭다운(파란 하이라이트)을 대체한다.
 * 장치 선택(prejoin) 등 옵션 목록 선택 UI 에 재사용한다.
 */
export function Select({
  options,
  value,
  onChange,
  placeholder = "선택",
  id,
  disabled = false,
  className = "",
  "data-testid": testId,
  "aria-label": ariaLabel,
  trigger,
  triggerClassName,
  placement = "bottom",
  listClassName = "inset-x-0",
}: SelectProps) {
  const [open, setOpen] = useState(false);
  const [highlightIndex, setHighlightIndex] = useState(0);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const listboxId = useId();

  const selectedIndex = options.findIndex((option) => option.value === value);
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null;

  // 바깥 클릭으로 닫기
  useDismissOnOutsidePointer(
    rootRef,
    open,
    useCallback(() => setOpen(false), []),
  );

  function openList() {
    if (disabled || options.length === 0) {
      return;
    }
    setHighlightIndex(selectedIndex >= 0 ? selectedIndex : 0);
    setOpen(true);
  }

  function selectAt(index: number) {
    const option = options[index];
    if (option) {
      onChange(option.value);
    }
    setOpen(false);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (!open) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        openList();
      }
      return;
    }
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        setHighlightIndex((index) => Math.min(index + 1, options.length - 1));
        break;
      case "ArrowUp":
        event.preventDefault();
        setHighlightIndex((index) => Math.max(index - 1, 0));
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        selectAt(highlightIndex);
        break;
      case "Escape":
      case "Tab":
        setOpen(false);
        break;
    }
  }

  return (
    <div ref={rootRef} className={`relative ${className}`}>
      <button
        type="button"
        role="combobox"
        id={id}
        data-testid={testId}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open ? `${listboxId}-${highlightIndex}` : undefined}
        aria-label={ariaLabel}
        onClick={() => (open ? setOpen(false) : openList())}
        onKeyDown={handleKeyDown}
        className={
          triggerClassName ??
          `flex w-full items-center justify-between gap-2 rounded-xl border bg-surface px-[15px] py-3 text-left text-sm font-semibold transition-colors ${
            open ? "border-line-primary" : "border-line-muted"
          } ${disabled ? "cursor-not-allowed bg-muted-surface text-ink-disabled" : "cursor-pointer hover:border-line-primary"} ${
            selected ? "text-ink" : "text-ink-faint"
          }`
        }
      >
        {trigger ? (
          trigger({ open, selected })
        ) : (
          <>
            <span className="truncate">{selected ? selected.label : placeholder}</span>
            <ChevronDownIcon
              className={`size-4 text-ink-faint transition-transform ${open ? "rotate-180" : ""}`}
            />
          </>
        )}
      </button>

      {open && (
        <ul
          id={listboxId}
          role="listbox"
          className={`absolute z-30 max-h-56 overflow-y-auto rounded-[14px] border border-line bg-surface p-1.5 shadow-pop animate-[zPop_.18s] ${
            placement === "top" ? "bottom-[calc(100%+6px)]" : "top-[calc(100%+6px)]"
          } ${listClassName}`}
        >
          {options.map((option, index) => {
            const isSelected = option.value === value;
            const isHighlighted = index === highlightIndex;
            return (
              <li
                key={option.value}
                id={`${listboxId}-${index}`}
                role="option"
                aria-selected={isSelected}
                onPointerMove={() => setHighlightIndex(index)}
                onClick={() => selectAt(index)}
                className={`flex cursor-pointer items-center justify-between gap-2 rounded-[10px] px-3 py-2.5 text-sm ${
                  isSelected
                    ? "bg-primary-soft font-bold text-primary-dark"
                    : isHighlighted
                      ? "bg-primary-softer text-ink"
                      : "text-ink-sub"
                }`}
              >
                <span className="truncate">{option.label}</span>
                {isSelected && <span className="shrink-0 text-xs font-black text-primary">✓</span>}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
