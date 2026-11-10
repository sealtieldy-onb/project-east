import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
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
  const selectedIndex = options.findIndex((option) => option.value === value);
  const selectedOption = selectedIndex >= 0 ? options[selectedIndex] : undefined;

  useLayoutEffect(() => {
    const menu = menuRef.current;
    if (!open) {
      if (menu && typeof menu.hidePopover === "function" && menu.matches(":popover-open")) menu.hidePopover();
      return;
    }

    const updatePosition = () => {
      const trigger = triggerRef.current;
      if (!trigger) return;
      const rect = trigger.getBoundingClientRect();
      const viewportWidth = document.documentElement.clientWidth;
      const viewportHeight = window.innerHeight;
      const width = Math.min(Math.max(rect.width, minimumMenuWidth), viewportWidth - 16);
      const left = Math.max(8, Math.min(
        alignMenu === "right" ? rect.right - width : rect.left,
        viewportWidth - width - 8,
      ));
      const below = Math.max(0, viewportHeight - rect.bottom - 8);
      const above = Math.max(0, rect.top - 8);
      const openAbove = below < Math.min(220, above) && above > below;
      const availableHeight = openAbove ? above : below;
      const maxHeight = Math.max(72, Math.min(360, availableHeight - 8, viewportHeight - 16));
      const top = openAbove
        ? Math.max(8, rect.top - maxHeight - 6)
        : Math.min(viewportHeight - maxHeight - 8, rect.bottom + 6);

      Object.assign(menu?.style ?? {}, { position: "fixed", top: `${top}px`, left: `${left}px`, width: `${width}px`, maxHeight: `${maxHeight}px`, zIndex: "1000" });
    };

    updatePosition();
    if (menu && typeof menu.showPopover === "function" && !menu.matches(":popover-open")) menu.showPopover();

    window.addEventListener("resize", updatePosition);
    document.addEventListener("scroll", updatePosition, true);
    return () => {
      window.removeEventListener("resize", updatePosition);
      document.removeEventListener("scroll", updatePosition, true);
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
      {menu}
    </div>
  );
}
