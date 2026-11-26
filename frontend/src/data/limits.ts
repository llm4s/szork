/**
 * The server's limits on what the player types (SzorkConfig's MaxCommandChars and MaxThemeChars): the
 * fields stop at them, so the player is never refused for typing too much.
 */

/** The longest command. */
export const MAX_COMMAND_CHARS = 1000;

/** The longest custom theme description. */
export const MAX_THEME_CHARS = 2000;

/** Whether a field holding `length` characters is near its `limit`, where it shows a counter. */
export const nearLimit = (length: number, limit: number): boolean => length > limit * 0.8;
