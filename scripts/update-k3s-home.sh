#!/bin/sh
# Called only after the corresponding image has been pushed successfully.
set +x
set -eu

if [ "${BRANCH_NAME:-}" != main ] || [ -n "${CHANGE_ID:-}" ]; then
    echo 'Skipping k3s-home update outside main.'
    exit 0
fi

component=${1:?Usage: update-k3s-home.sh backend|frontend|ai-service}
case "$component" in
    backend) manifests='apps/eventpulse/api-deployment.yaml apps/eventpulse/worker-deployment.yaml' ;;
    frontend) manifests='apps/eventpulse/frontend-deployment.yaml' ;;
    ai-service) manifests='apps/eventpulse/ai-service-deployment.yaml' ;;
    *) echo "Unknown component: $component" >&2; exit 1 ;;
esac

# Use the exact image from the preceding build stage, never recompute its tag
# after cloning the configuration repository. Seeder Jobs are updated separately.
: "${FULL_IMAGE:?The image build stage must set FULL_IMAGE}"
image_repository="ghcr.io/kaiwenyao/eventpulse-$component"
image_tag=${FULL_IMAGE##*:}
if [ "$FULL_IMAGE" != "$image_repository:$image_tag" ] ||
   ! printf '%s\n' "$image_tag" | grep -Eq '^[0-9a-f]{7}$'; then
    echo "Unexpected image for $component: $FULL_IMAGE" >&2
    exit 1
fi
: "${GITOPS_USER:?Bind the k3s-home-write username}"
: "${GITOPS_TOKEN:?Bind the k3s-home-write token}"

umask 077
gitops_tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/eventpulse-gitops.XXXXXX")
trap 'rm -rf "$gitops_tmpdir"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cat > "$gitops_tmpdir/askpass" <<'ASKPASS'
#!/bin/sh
case "$1" in
    *Username*) printf '%s\n' "$GITOPS_USER" ;;
    *Password*) printf '%s\n' "$GITOPS_TOKEN" ;;
esac
ASKPASS
chmod 700 "$gitops_tmpdir/askpass"
export GIT_ASKPASS="$gitops_tmpdir/askpass"
export GIT_TERMINAL_PROMPT=0

# The override allows integration tests to use a local bare repository.
gitops_url=${GITOPS_REPO_URL:-https://github.com/kaiwenyao/k3s-home.git}
gitops_checkout="$gitops_tmpdir/repo"
git clone --single-branch --branch main -- "$gitops_url" "$gitops_checkout"
git -C "$gitops_checkout" config user.name Jenkins
git -C "$gitops_checkout" config user.email jenkins@eventpulse.local

attempt=1
while [ "$attempt" -le 5 ]; do
    # All three pipelines write to main. Reapply only our image change to the
    # latest remote tree after a rejected push, preserving other jobs' changes.
    # This reset affects only our disposable clone, never the Jenkins workspace.
    git -C "$gitops_checkout" fetch origin main
    git -C "$gitops_checkout" reset --hard origin/main
    for manifest in $manifests; do
        if [ ! -f "$gitops_checkout/$manifest" ]; then
            echo "Missing GitOps manifest: $manifest" >&2
            exit 1
        fi
        if ! awk -v prefix="$image_repository:" -v image="$FULL_IMAGE" '
            $1 == "image:" && index($2, prefix) == 1 {
                matches++
                sub(/image:[[:space:]]*[^[:space:]]+/, "image: " image)
            }
            { print }
            END { if (matches != 1) exit 1 }
        ' "$gitops_checkout/$manifest" > "$gitops_tmpdir/manifest"; then
            echo "Expected exactly one $image_repository image in $manifest" >&2
            exit 1
        fi
        # Avoid a formatting-only commit when awk adds a final newline.
        if ! awk -v image="$FULL_IMAGE" '$1 == "image:" && $2 == image { found++ } END { exit(found != 1) }' "$gitops_checkout/$manifest"; then
            cat "$gitops_tmpdir/manifest" > "$gitops_checkout/$manifest"
        fi
    done

    # Word splitting is intentional: manifests is a fixed allowlist above.
    # shellcheck disable=SC2086
    if git -C "$gitops_checkout" diff --quiet -- $manifests; then
        echo "k3s-home already uses $FULL_IMAGE."
        exit 0
    fi
    # shellcheck disable=SC2086
    git -C "$gitops_checkout" add -- $manifests
    git -C "$gitops_checkout" commit -m "deploy(eventpulse-$component): $image_tag"
    if git -C "$gitops_checkout" push origin HEAD:main; then
        echo "Updated k3s-home: $FULL_IMAGE"
        exit 0
    fi
    echo "GitOps push failed (attempt $attempt/5); refreshing main before retry." >&2
    attempt=$((attempt + 1))
done

echo 'Unable to push k3s-home after 5 attempts.' >&2
exit 1
