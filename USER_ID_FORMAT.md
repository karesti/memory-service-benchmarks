# User ID Format Configuration

## Overview

The LoCoMo benchmark now supports configurable user ID formats through the `benchmark.user-id-format` property. This allows you to customize how user identities are generated for each conversation.

## Default Behavior

**By default** (when `benchmark.user-id-format` is not set), the benchmark uses the speaker_a's actual name from the dataset:

```
Conversation 0: Alice & Bob → userId = "alice"
Conversation 1: Charlie & Dana → userId = "charlie"
```

Names are automatically sanitized for Keycloak compatibility:
- Converted to lowercase
- Special characters replaced with underscores
- Example: "Mary Jane" → "mary_jane"

## Configuration

Set the `benchmark.user-id-format` property in `application.properties` or via command line:

```properties
# In application.properties
benchmark.user-id-format=locomo_{convIdx}

# Or via command line
java -Dbenchmark.user-id-format=locomo_{convIdx} -jar target/quarkus-app/quarkus-run.jar locomo
```

## Supported Placeholders

| Placeholder | Description | Example Value |
|-------------|-------------|---------------|
| `{convIdx}` | Conversation index (0-9) | `0`, `1`, `2` |
| `{speakerA}` | Speaker A's name from dataset | `Alice`, `Bob` |
| `{speakerB}` | Speaker B's name from dataset | `Bob`, `Charlie` |

## Examples

### Use Original Format (locomo_N)

```properties
benchmark.user-id-format=locomo_{convIdx}
```

Result: `locomo_0`, `locomo_1`, `locomo_2`, etc.

### Use Speaker Name with Conversation Index

```properties
benchmark.user-id-format={speakerA}_conv{convIdx}
```

Result: `alice_conv0`, `charlie_conv1`, etc.

### Use Custom Prefix

```properties
benchmark.user-id-format=user_{speakerA}
```

Result: `user_alice`, `user_charlie`, etc.

### Use Both Speakers

```properties
benchmark.user-id-format={speakerA}_and_{speakerB}
```

Result: `alice_and_bob`, `charlie_and_dana`, etc.

## Use Cases

### 1. Legacy Compatibility

If you have existing data with the old `locomo_N` format:

```bash
java -Dbenchmark.user-id-format=locomo_{convIdx} -jar target/quarkus-app/quarkus-run.jar locomo
```

### 2. Readable User Names (Default)

For more intuitive logs and Keycloak admin console:

```bash
# Uses default: speaker_a name
java -jar target/quarkus-app/quarkus-run.jar locomo
```

### 3. Unique Identifiers

If you're concerned about name collisions across conversations:

```bash
java -Dbenchmark.user-id-format={speakerA}_conv{convIdx} -jar target/quarkus-app/quarkus-run.jar locomo
```

## Impact on System

### Keycloak Users

User IDs are used to create Keycloak users:

- Default: `alice@benchmark.local`
- With `locomo_{convIdx}`: `locomo_0@benchmark.local`

### Memory Namespaces

Memory namespaces follow the pattern `["user", userId, "cognition.v1"]`:

- Default: `["user", "alice", "cognition.v1"]`
- With `locomo_{convIdx}`: `["user", "locomo_0", "cognition.v1"]`

### Log Output

```
=== Conversation 0: Alice (USER) & Bob (AI) (5 sessions, 150 questions) ===
Created conversation locomo-conv-0 for benchmark user=alice (oidc user=alice)
```

## Notes

- The format string is applied **per conversation**, not globally
- Placeholder values are taken from the dataset's `speaker_a` and `speaker_b` fields
- Special characters in the resulting userId are **not** automatically sanitized when using custom formats
- For Keycloak compatibility, ensure your format produces valid usernames (alphanumeric + underscore/hyphen)
