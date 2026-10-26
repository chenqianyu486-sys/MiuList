import type { Task, TaskTypeOption } from './types';

/** App 内置的三类待办。颜色与 `ui/theme/TaskType` 一一对应。 */
export const BUILTIN_TASK_TYPES: TaskTypeOption[] = [
  { name: 'PRE_STUDY', label: '课前预习', colorHex: '#A6D6E4', inkHex: '#3F5F69' },
  { name: 'POST_CLASS', label: '课后作业', colorHex: '#CFC6EE', inkHex: '#5F4F94' },
  { name: 'LAB_REPORT', label: '实验报告', colorHex: '#B3E0BE', inkHex: '#43634B' },
];

/** 旧网页端 / 手机快照里出现过的短标签，映射回内置类别。 */
const ALIASES: Record<string, string> = {
  作业: 'POST_CLASS',
  课后作业: 'POST_CLASS',
  预习: 'PRE_STUDY',
  课前预习: 'PRE_STUDY',
  实验报告: 'LAB_REPORT',
};

const NEUTRAL: TaskTypeOption = {
  name: '',
  label: '',
  colorHex: '#E8ECE9',
  inkHex: '#4A524D',
  isCustom: true,
};

/**
 * 手机发来的自定义分类长这样：`{id, label, colorHex, inkHex, sortOrder}` ——
 * **没有 `name`**。而 `name` 才是落库的 key（`CUSTOM:<id>`）。
 *
 * 不补这个 key 的后果：所有自定义分类的 `name` 都是空串，
 * 于是「新建待办」里的下拉框选哪个自定义分类，存进去的都是「未分类」，
 * 而且两个自定义分类还会共用同一个空 value，互相顶掉。
 */
export function taskTypeOptions(custom: TaskTypeOption[] = []): TaskTypeOption[] {
  const out = [...BUILTIN_TASK_TYPES];
  const seen = new Set(out.map((v) => v.name));
  for (const item of custom) {
    if (!item || !item.label) continue;
    const name = item.name || (item.id ? `CUSTOM:${item.id}` : '');
    if (!name || seen.has(name)) continue;
    out.push({ ...NEUTRAL, ...item, name, isCustom: true });
    seen.add(name);
  }
  return out;
}

export function normalizeTaskType(raw: string, label: string, custom: TaskTypeOption[] = []): TaskTypeOption {
  const key = ALIASES[label] || ALIASES[raw] || raw || label;
  const options = taskTypeOptions(custom);
  const customHit = options.find(
    (v) => v.isCustom && (v.name === key || v.label === label || v.label === raw),
  );
  const builtin = BUILTIN_TASK_TYPES.find((v) => v.name === key || v.label === label || v.label === raw);
  const found = customHit || builtin;
  if (found) return found;
  if (!label) return NEUTRAL;
  return { ...NEUTRAL, name: key, label, isCustom: true };
}

export function taskTypeOf(task: Task, custom: TaskTypeOption[] = []): TaskTypeOption {
  return normalizeTaskType(task.taskType || '', task.taskTypeLabel || '', custom);
}
