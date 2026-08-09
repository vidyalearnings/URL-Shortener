param(
    [switch]$Interactive
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$args = @(
    "-jar", "orchestrator\target\orchestrator.jar",
    "run-scenario",
    "--graph", "orchestrator\src\main\resources\graphs\graph.yaml",
    "--requirements-input", "orchestrator\scenarios\inputs\requirements-ambiguous.md",
    "--run-dir", "orchestrator\runs\ambiguous",
    "--service-repo", "service"
)
if ($Interactive) {
    $args += "--interactive"
} else {
    $args += "--approvals"
    $args += "orchestrator\scenarios\approvals\ambiguous-approvals.yaml"
}

& java @args
