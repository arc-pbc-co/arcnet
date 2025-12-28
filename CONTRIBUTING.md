# Contributing to ArcNet

Thank you for your interest in contributing to ArcNet! This document provides guidelines and instructions for contributing to the project.

## Code of Conduct

We are committed to providing a welcoming and inclusive environment for all contributors. Please be respectful and constructive in all interactions.

## Getting Started

### Prerequisites

- Java 11+ (OpenJDK recommended)
- Clojure CLI 1.11+
- Node.js 18+ and npm
- Docker and Docker Compose
- Git

### Setup Development Environment

1. **Fork the repository** on GitHub

2. **Clone your fork**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/arcnet.git
   cd arcnet
   ```

3. **Add upstream remote**:
   ```bash
   git remote add upstream https://github.com/arc-pbc-co/arcnet.git
   ```

4. **Install dependencies**:
   ```bash
   # Backend
   cd arcnet-protocol
   clojure -P
   
   # Frontend
   cd ../arcnet-console
   npm install
   ```

5. **Start infrastructure**:
   ```bash
   cd arcnet-protocol
   docker-compose up -d
   ```

See [Development Guide](docs/development.md) for detailed setup instructions.

## How to Contribute

### Reporting Bugs

1. **Search existing issues** to avoid duplicates
2. **Create a new issue** with:
   - Clear, descriptive title
   - Steps to reproduce
   - Expected vs. actual behavior
   - Environment details (OS, versions)
   - Relevant logs or screenshots

### Suggesting Features

1. **Check existing issues** for similar suggestions
2. **Create a new issue** with:
   - Clear description of the feature
   - Use cases and benefits
   - Potential implementation approach
   - Any relevant examples or mockups

### Submitting Changes

1. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes**:
   - Follow code style guidelines (see below)
   - Write clear, descriptive commit messages
   - Add tests for new functionality
   - Update documentation as needed

3. **Test your changes**:
   ```bash
   # Backend tests
   cd arcnet-protocol
   clojure -M:test
   
   # Frontend type checking
   cd arcnet-console
   npm run build
   npm run lint
   ```

4. **Commit your changes**:
   ```bash
   git add .
   git commit -m "feat: add new feature description"
   ```

5. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

6. **Create a Pull Request**:
   - Go to the original repository on GitHub
   - Click "New Pull Request"
   - Select your branch
   - Fill out the PR template
   - Link related issues

## Code Style Guidelines

### Clojure (Backend)

- Use **kebab-case** for function and variable names
- Use **namespaced keywords** for domain entities (`:node/id`, `:request/priority`)
- Prefer **pure functions** over stateful operations
- Document public functions with **docstrings**
- Keep functions **small and focused** (< 30 lines)
- Use `let` for intermediate values
- Avoid deeply nested code (max 3 levels)

**Example**:
```clojure
(defn score-candidate
  "Calculate a score for a candidate node based on request requirements.
   Higher scores indicate better matches."
  [node request]
  (+ (* 100 (if (= (:geozone node) (:requester-geozone request)) 1 0))
     (* 50 (if (= (:energy-source node) :cogen) 1 0))
     (* 30 (- 1.0 (:gpu-utilization node)))
     (* 20 (:battery-level node))))
```

**Formatting**:
```bash
# Format code
clojure -M:cljfmt fix

# Check formatting
clojure -M:cljfmt check
```

### TypeScript (Frontend)

- Use **strict mode**
- Prefer **interfaces** over types for object shapes
- Use **enums** for fixed sets of values
- Avoid `any` type
- Document complex types with **JSDoc comments**
- Use **functional components** with hooks
- Keep components **small** (< 200 lines)
- Extract **custom hooks** for reusable logic

**Example**:
```typescript
/**
 * Represents a compute node in the ArcNet mesh.
 */
interface Node {
  id: string;
  geohash: string;
  energySource: 'cogen' | 'grid' | 'battery';
  batteryLevel: number;
  gpuUtilization: number;
  modelsLoaded: string[];
  timestamp: string;
}

/**
 * Custom hook for managing node selection.
 */
function useNodeSelection() {
  const selectedNodeId = useArcnetStore((state) => state.selectedNodeId);
  const selectNode = useArcnetStore((state) => state.selectNode);
  
  return { selectedNodeId, selectNode };
}
```

## Commit Message Guidelines

Follow [Conventional Commits](https://www.conventionalcommits.org/):

**Format**:
```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types**:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

**Examples**:
```
feat(scheduler): add energy-aware routing algorithm
fix(console): resolve WebSocket reconnection issue
docs(api): update WebSocket protocol documentation
refactor(state): simplify XTDB query logic
test(scheduler): add tests for candidate scoring
```

## Pull Request Guidelines

### PR Title

Use the same format as commit messages:
```
feat(scheduler): add energy-aware routing algorithm
```

### PR Description

Include:
- **Summary**: What does this PR do?
- **Motivation**: Why is this change needed?
- **Changes**: What specific changes were made?
- **Testing**: How was this tested?
- **Related Issues**: Link to related issues

**Template**:
```markdown
## Summary
Brief description of the changes.

## Motivation
Why is this change needed? What problem does it solve?

## Changes
- Change 1
- Change 2
- Change 3

## Testing
- [ ] Unit tests pass
- [ ] Manual testing completed
- [ ] Documentation updated

## Related Issues
Closes #123
```

### Review Process

1. **Automated checks** must pass (tests, linting)
2. **At least one approval** from a maintainer
3. **All comments addressed** or resolved
4. **Documentation updated** if needed
5. **No merge conflicts** with main branch

## Testing Guidelines

### Backend Tests

```bash
cd arcnet-protocol

# Run all tests
clojure -M:test

# Run specific namespace
clojure -M:test -n arcnet.scheduler.core-test

# Run with coverage
clojure -M:test:coverage
```

**Test Structure**:
```clojure
(ns arcnet.scheduler.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [arcnet.scheduler.core :as scheduler]))

(deftest test-score-candidate
  (testing "Scoring with same geozone"
    (let [node {:geozone "CAISO"
                :energy-source :cogen
                :gpu-utilization 0.5
                :battery-level 0.8}
          request {:requester-geozone "CAISO"}]
      (is (> (scheduler/score-candidate node request) 100))))
  
  (testing "Scoring with different geozone"
    (let [node {:geozone "ERCOT"
                :energy-source :cogen
                :gpu-utilization 0.5
                :battery-level 0.8}
          request {:requester-geozone "CAISO"}]
      (is (< (scheduler/score-candidate node request) 100)))))
```

### Frontend Tests

```bash
cd arcnet-console

# Type checking
npm run build

# Linting
npm run lint
```

## Documentation Guidelines

- Update documentation for **all user-facing changes**
- Use **clear, concise language**
- Include **code examples** where appropriate
- Add **diagrams** for complex concepts
- Keep documentation **up-to-date** with code changes

## Questions?

- **Documentation**: [docs/index.md](docs/index.md)
- **Development Guide**: [docs/development.md](docs/development.md)
- **GitHub Issues**: [Create an issue](https://github.com/arc-pbc-co/arcnet/issues)

## License

By contributing to ArcNet, you agree that your contributions will be licensed under the same license as the project.

---

Thank you for contributing to ArcNet! 🚀

