import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

/**
 * 合并多个 Tailwind CSS class 名称。
 *
 * - `clsx` 处理条件表达式、数组、对象等语法糖，将入参拍平为单个 class 字符串。
 * - `tailwind-merge` 去除冲突的 Tailwind 工具类（后出现的优先），
 *   例如同时传入 `px-2` 和 `px-4` 时只保留 `px-4`。
 *
 * @example
 * cn("px-2 py-1", isActive && "bg-blue-500", "px-4")
 * // → "py-1 bg-blue-500 px-4"  （px-2 被 px-4 覆盖）
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
