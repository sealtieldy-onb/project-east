import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { Check, ChevronDown } from "lucide-react";
import "./custom-dropdown.css";

export type CustomDropdownOption = {
  value: string;
  label: string;
  disabled?: boolean;
};

type CustomDropdownProps = {
  value: string;
  options: CustomDropdownOption[];
  onChange: (value: string) => void;
  ariaLabel: string;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  triggerClassName?: string;
  menuClassName?: string;
  alignMenu?: "left" | "right";
  minimumMenuWidth?: number;
};

export function CustomDropdown({
  value,
  options,
  onChange,
  ariaLabel,
  disabled = false,
  placeholder = "Choose an option",
  className = "",
  triggerClassName = "",
  menuClassName = "",
  alignMenu = "left",
  minimumMenuWidth = 220,
}: CustomDropdownProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const menuId = useId();
  const supportsPopover = typeof HTMLElement !== "undefined" && "showPopover" in HTMLElement.prototype;
  const fallbackDialog = !supportsPopover ? rootRef.current?.closest("dialog") ?? null : null;
  const portalTarget = !supportsPopover && typeof document !== "undefined"
    ? fallbackDialog ?? document.body
    : null;
  const selectedIndex = options.findIndex((option) => option.value === value);
  const selectedOption = selectedIndex >= 0 ? options[selectedIndex] : undefined;

  useLayoutEffect(() => {
    const menu = menuRef.current;
    if (!open) {
      if (menu && typeof menu.hidePopover === "function") {
        try {
          menu.hidePopover();
        } catch {
          // A menu that was not opened as a popover is already hidden by its data attribute.
        }
      }
      return;
    }
    if (!menu) return;

    const updatePosition = () => {
      const trigger = triggerRef.current;
      if (!trigger || !menu) return;
      const rect = trigger.getBoundingClientRect();
      const visualViewport = window.visualViewport;
      const layoutWidth = document.documentElement.clientWidth || window.innerWidth;
      const layoutHeight = document.documentElement.clientHeight || window.innerHeight;
      const viewport = {
        left: visualViewport?.offsetLeft ?? 0,
        top: visualViewport?.offsetTop ?? 0,
        width: visualViewport?.width ?? layoutWidth,
        height: visualViewport?.height ?? layoutHeight,
      };
      const dialogRect = fallbackDialog?.getBoundingClientRect();
      const dialogScaleX = fallbackDialog?.offsetWidth && dialogRect
        ? dialogRect.width / fallbackDialog.offsetWidth
        : 1;
      const dialogScaleY = fallbackDialog?.offsetHeight && dialogRect
        ? dialogRect.height / fallbackDialog.offsetHeight
        : 1;
      if (fallbackDialog && dialogRect) {
        const dialogLeft = dialogRect.left + fallbackDialog.clientLeft * dialogScaleX;
        const dialogTop = dialogRect.top + fallbackDialog.clientTop * dialogScaleY;
        const dialogRight = dialogRect.right - (fallbackDialog.offsetWidth - fallbackDialog.clientLeft - fallbackDialog.clientWidth) * dialogScaleX;
        const dialogBottom = dialogRect.bottom - (fallbackDialog.offsetHeight - fallbackDialog.clientTop - fallbackDialog.clientHeight) * dialogScaleY;
        const right = Math.min(viewport.left + viewport.width, dialogRight);
        const bottom = Math.min(viewport.top + viewport.height, dialogBottom);
        viewport.left = Math.max(viewport.left, dialogLeft);
        viewport.top = Math.max(viewport.top, dialogTop);
        viewport.width = Math.max(0, right - viewport.left);
        viewport.height = Math.max(0, bottom - viewport.top);
      }
      const viewportRight = viewport.left + viewport.width;
      const viewportBottom = viewport.top + viewport.height;
      const edgePadding = Math.min(8, viewport.width / 2, viewport.height / 2);
      const availableWidth = Math.max(0, viewport.width - edgePadding * 2);
      const requestedMinimum = Math.max(0, minimumMenuWidth);
      const width = Math.min(Math.max(rect.width, Math.min(requestedMinimum, availableWidth)), availableWidth);
      const minLeft = viewport.left + edgePadding;
      const maxLeft = Math.max(minLeft, viewportRight - edgePadding - width);
      const requestedLeft = alignMenu === "right" ? rect.right - width : rect.left;
      const left = Math.max(minLeft, Math.min(requestedLeft, maxLeft));

      // Measure after setting width so wrapped labels contribute their real height.
      menu.style.width = `${width}px`;
      const naturalHeight = Math.min(menu.scrollHeight, 360);
      const gap = 6;
      const below = Math.max(0, viewportBottom - rect.bottom - gap - edgePadding);
      const above = Math.max(0, rect.top - viewport.top - gap - edgePadding);
      const openAbove = below < Math.min(naturalHeight, 220) && above > below;
      const availableHeight = openAbove ? above : below;
      const maxHeight = Math.max(0, Math.min(360, availableHeight));
      const top = openAbove
        ? Math.max(viewport.top + edgePadding, rect.top - gap - maxHeight)
        : Math.min(viewportBottom - edgePadding - maxHeight, rect.bottom + gap);

      let position = "fixed";
      let positionedLeft = left;
      let positionedTop = top;
      if (fallbackDialog && dialogRect) {
        position = "absolute";
        positionedLeft = (left - dialogRect.left) / dialogScaleX - fallbackDialog.clientLeft;
        positionedTop = (top - dialogRect.top) / dialogScaleY - fallbackDialog.clientTop;
      }

      Object.assign(menu.style, {
        position,
        top: `${positionedTop}px`,
        left: `${positionedLeft}px`,
        width: `${width}px`,
        maxHeight: `${maxHeight}px`,
        zIndex: "1000",
      });
    };

    updatePosition();
    if (typeof menu.showPopover === "function") {
      try {
        menu.showPopover();
      } catch {
        // Keep the positioned, data-controlled menu usable in browsers with partial Popover API support.
      }
    }

    window.addEventListener("resize", updatePosition);
    document.addEventListener("scroll", updatePosition, true);
    window.visualViewport?.addEventListener("resize", updatePosition);
    window.visualViewport?.addEventListener("scroll", updatePosition);
    const resizeObserver = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(updatePosition);
    if (triggerRef.current) resizeObserver?.observe(triggerRef.current);
    resizeObserver?.observe(menu);
    return () => {
      window.removeEventListener("resize", updatePosition);
      document.removeEventListener("scroll", updatePosition, true);
      window.visualViewport?.removeEventListener("resize", updatePosition);
      window.visualViewport?.removeEventListener("scroll", updatePosition);
      resizeObserver?.disconnect();
    };
  }, [alignMenu, minimumMenuWidth, open]);

  useEffect(() => {
    if (!open) return;
    const outsidePointer = (event: PointerEvent) => {
      const target = event.target;
      if (target instanceof Node && !rootRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
    };
    const outsideFocus = (event: FocusEvent) => {
      const target = event.target;
      if (target instanceof Node && !rootRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
    };
    document.addEventListener("pointerdown", outsidePointer);
    document.addEventListener("focusin", outsideFocus);
    return () => {
      document.removeEventListener("pointerdown", outsidePointer);
      document.removeEventListener("focusin", outsideFocus);
    };
  }, [open]);

  useEffect(() => {
    if (open) {
      const currentOptions = optionsRef.current;
      const focusIndex = selectedIndex >= 0 && !currentOptions[selectedIndex]?.disabled
        ? selectedIndex
        : currentOptions.findIndex((option) => !option.disabled);
      if (focusIndex >= 0) optionRefs.current[focusIndex]?.focus();
    }
  }, [open, selectedIndex]);

  function focusOption(index: number) {
    if (options.length === 0) return;
    for (let offset = 0; offset < options.length; offset += 1) {
      const candidate = (index + offset + options.length) % options.length;
      if (!options[candidate]?.disabled) {
        optionRefs.current[candidate]?.focus();
        return;
      }
    }
  }

  function closeMenu(restoreFocus: boolean) {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  }

  function handleTriggerKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>) {
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    if (!open) setOpen(true);
    else focusOption(selectedIndex + (event.key === "ArrowDown" ? 1 : -1));
  }

  function handleOptionKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>, index: number) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusOption(index + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      focusOption(index - 1);
    } else if (event.key === "Home") {
      event.preventDefault();
      focusOption(0);
    } else if (event.key === "End") {
      event.preventDefault();
      focusOption(options.length - 1);
    } else if (event.key === "Escape") {
      event.preventDefault();
      closeMenu(true);
    }
  }

  const menu = (
    <div
      id={menuId}
      ref={menuRef}
      className={`custom-dropdown-menu ${menuClassName}`.trim()}
      role="listbox"
      aria-label={ariaLabel}
      popover="manual"
      data-open={open}
    >
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <button
            type="button"
            role="option"
            aria-selected={selected}
            className="custom-dropdown-option"
            key={`${option.value}-${index}`}
            ref={(element) => { optionRefs.current[index] = element; }}
            tabIndex={-1}
            disabled={option.disabled}
            onKeyDown={(event) => handleOptionKeyDown(event, index)}
            onClick={() => {
              onChange(option.value);
              closeMenu(true);
            }}
          >
            <span>{option.label}</span>
            {selected ? <Check size={14} aria-hidden="true" /> : null}
          </button>
        );
      })}
    </div>
  );

  return (
    <div className={`custom-dropdown ${className}`.trim()} ref={rootRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`custom-dropdown-trigger ${triggerClassName}`.trim()}
        aria-label={`${ariaLabel}: ${selectedOption?.label ?? placeholder}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="custom-dropdown-value">{selectedOption?.label ?? placeholder}</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {portalTarget ? createPortal(menu, portalTarget) : menu}
    </div>
  );
}
