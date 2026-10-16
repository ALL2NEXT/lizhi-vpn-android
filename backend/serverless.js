/*
 * Cloudflare Worker deployment entry.
 *
 * Keep this file stable for existing deployment commands. Implementation is
 * composed from the modules in ./src.
 */
export { default } from "./src/worker/entry.ts";
