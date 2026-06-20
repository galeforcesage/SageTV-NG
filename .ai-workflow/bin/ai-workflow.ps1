param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$RemainingArgs
)

$scriptPath = Join-Path -Path $PSScriptRoot -ChildPath "ai_workflow.py"
py -3 $scriptPath @RemainingArgs
exit $LASTEXITCODE