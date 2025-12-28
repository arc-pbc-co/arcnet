/**
 * Command Line Parser
 * 
 * Parses command strings into structured command objects.
 * Supports: command --flag=value --boolean-flag args
 */

export interface ParsedCommand {
  command: string;
  args: string[];
  flags: Record<string, string | boolean>;
  raw: string;
}

export interface ParseError {
  message: string;
  position?: number;
}

/**
 * Parse a command string into structured format
 * 
 * Examples:
 *   "status" -> { command: "status", args: [], flags: {} }
 *   "nodes --status=online" -> { command: "nodes", args: [], flags: { status: "online" } }
 *   "select node-001 --fly" -> { command: "select", args: ["node-001"], flags: { fly: true } }
 *   "route node-001 node-002 --priority=high" -> { command: "route", args: ["node-001", "node-002"], flags: { priority: "high" } }
 */
export function parseCommand(input: string): ParsedCommand | ParseError {
  const trimmed = input.trim();
  
  if (!trimmed) {
    return { message: 'Empty command' };
  }

  const tokens = tokenize(trimmed);
  
  if (tokens.length === 0) {
    return { message: 'No command specified' };
  }

  const command = tokens[0].toLowerCase();
  const args: string[] = [];
  const flags: Record<string, string | boolean> = {};

  for (let i = 1; i < tokens.length; i++) {
    const token = tokens[i];
    
    if (token.startsWith('--')) {
      // Parse flag
      const flagStr = token.slice(2);
      const eqIndex = flagStr.indexOf('=');
      
      if (eqIndex === -1) {
        // Boolean flag: --verbose
        flags[flagStr] = true;
      } else {
        // Value flag: --status=online
        const key = flagStr.slice(0, eqIndex);
        const value = flagStr.slice(eqIndex + 1);
        
        if (!key) {
          return { message: `Invalid flag format: ${token}`, position: i };
        }
        
        // Remove quotes if present
        flags[key] = value.replace(/^["']|["']$/g, '');
      }
    } else if (token.startsWith('-') && token.length === 2) {
      // Short flag: -v
      const flag = token.slice(1);
      flags[flag] = true;
    } else {
      // Regular argument
      args.push(token);
    }
  }

  return {
    command,
    args,
    flags,
    raw: trimmed,
  };
}

/**
 * Tokenize command string, respecting quotes
 */
function tokenize(input: string): string[] {
  const tokens: string[] = [];
  let current = '';
  let inQuotes = false;
  let quoteChar = '';

  for (let i = 0; i < input.length; i++) {
    const char = input[i];
    
    if ((char === '"' || char === "'") && !inQuotes) {
      inQuotes = true;
      quoteChar = char;
      current += char;
    } else if (char === quoteChar && inQuotes) {
      inQuotes = false;
      current += char;
      quoteChar = '';
    } else if (char === ' ' && !inQuotes) {
      if (current) {
        tokens.push(current);
        current = '';
      }
    } else {
      current += char;
    }
  }

  if (current) {
    tokens.push(current);
  }

  return tokens;
}

/**
 * Check if parsed result is an error
 */
export function isParseError(result: ParsedCommand | ParseError): result is ParseError {
  return 'message' in result && !('command' in result);
}

/**
 * Validate flag value against allowed values
 */
export function validateFlag(
  flags: Record<string, string | boolean>,
  flagName: string,
  allowedValues: string[]
): string | null {
  const value = flags[flagName];
  
  if (value === undefined) {
    return null;
  }
  
  if (typeof value === 'boolean') {
    return `Flag --${flagName} requires a value`;
  }
  
  if (!allowedValues.includes(value)) {
    return `Invalid value for --${flagName}: ${value}. Allowed: ${allowedValues.join(', ')}`;
  }
  
  return null;
}

