<#
.SYNOPSIS
  Generates a formatted context string from specified files and folders for an LLM.

.DESCRIPTION
  This script takes a list of file or folder paths and creates a single text block.
  For each file, it includes a header with the file's relative path from a determined
  root, followed by the content of the file.

  The root path can be specified with -RootPath. If not, it's inferred:
  - If a single directory is provided as input, that directory becomes the root.
  - Otherwise (multiple inputs, or single file input), the current working directory ($PWD) is used as the root.

.PARAMETER Path
  An array of strings specifying the paths to files or folders to include.
  This parameter accepts pipeline input. Aliased to InputPath.

.PARAMETER RootPath
  Specifies the root directory for calculating relative paths.
  If not provided, it's inferred based on the input paths or defaults to the current working directory.

.EXAMPLE
  .\Create-LlmContext.ps1 -Path .\src\featureA, .\README.md
  # Processes all files in .\src\featureA and the .\README.md file.
  # Relative paths will be calculated from the current directory.

.EXAMPLE
  Get-ChildItem -Path .\src\utils -Filter *.js | .\Create-LlmContext.ps1
  # Processes all .js files in .\src\utils.
  # Relative paths will be calculated from the current directory.

.EXAMPLE
  .\Create-LlmContext.ps1 -Path .\myproject\src -RootPath .\myproject
  # Processes all files in .\myproject\src.
  # Relative paths will be calculated from .\myproject (e.g., src\file.txt).

.EXAMPLE
  .\Create-LlmContext.ps1 -Path .\myproject\src
  # Processes all files in .\myproject\src.
  # Relative paths will be calculated from .\myproject\src (e.g., file.txt).

.OUTPUTS
  System.String
  The formatted context string is written to the standard output.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ValueFromPipeline = $true, Position = 0)]
    [Alias('InputPath')]
    [string[]]$Path,

    [Parameter(Mandatory = $false)]
    [string]$RootPath = $PWD
)

begin {
    # Initialize lists to collect inputs during the 'process' phase.
    # This is crucial for handling pipeline input correctly.
    $allInputPaths = [System.Collections.Generic.List[string]]::new()
    $filesToProcess = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
    Write-Verbose "Initialization complete. Awaiting input..."
}

process {
    # This block runs for each item from the pipeline or the -Path parameter.
    # We collect all file objects and the original input paths.
    $allInputPaths.AddRange($Path)

    foreach ($itemPath in $Path) {
        Write-Verbose "Processing input path: $itemPath"
        try {
            $resolvedItem = Resolve-Path -Path $itemPath -ErrorAction SilentlyContinue
            if (-not $resolvedItem) {
                Write-Warning "Path not found or inaccessible: $itemPath"
                continue
            }

            if (Test-Path -Path $resolvedItem.ProviderPath -PathType Container) {
                # It's a directory, get all files recursively
                Write-Verbose "Expanding directory: $($resolvedItem.ProviderPath)"
                Get-ChildItem -Path $resolvedItem.ProviderPath -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
                    $filesToProcess.Add($_)
                }
            }
            elseif (Test-Path -Path $resolvedItem.ProviderPath -PathType Leaf) {
                # It's a file
                Write-Verbose "Queueing file: $($resolvedItem.ProviderPath)"
                $filesToProcess.Add((Get-Item -Path $resolvedItem.ProviderPath))
            }
            else {
                Write-Warning "Path '$($resolvedItem.ProviderPath)' is neither a file nor a directory, or is inaccessible."
            }
        }
        catch {
            Write-Warning "Error processing path '$itemPath': $($_.Exception.Message)"
        }
    }
}

end {
    Write-Verbose "All input processed. Finalizing context..."

    if ($filesToProcess.Count -eq 0) {
        Write-Warning "No files found to process."
        return
    }

    # Deduplicate files in case of overlapping inputs (e.g., providing a folder and a file within it)
    $uniqueFiles = $filesToProcess | Sort-Object -Property FullName -Unique
    Write-Verbose "Total unique files to process: $($uniqueFiles.Count)"


    # --- Determine the root path for relative path calculations ---
    # This logic is now in the 'end' block to work correctly with pipeline input.
    $resolvedRootPath = ""
    Write-Verbose "Determining effective root path..."
    Write-Verbose "User-specified RootPath: $RootPath"
    Write-Verbose "Total unique input paths received: $($allInputPaths.Count)"

    if (-not [string]::IsNullOrWhiteSpace($RootPath)) {
        try {
            $resolvedRootPath = (Resolve-Path -Path $RootPath -ErrorAction Stop).ProviderPath
            if (-not (Test-Path -Path $resolvedRootPath -PathType Container)) {
                Write-Error "Specified RootPath '$RootPath' is not a valid directory."
                return # Use return instead of exit
            }
        }
        catch {
            Write-Error "Could not resolve specified RootPath '$RootPath': $($_.Exception.Message)"
            return
        }
    }
    elseif ($allInputPaths.Count -eq 1 -and (Test-Path -Path $allInputPaths[0] -PathType Container)) {
        # If a single directory was provided as input, use it as the root
        $resolvedRootPath = (Resolve-Path -Path $allInputPaths[0]).ProviderPath
        Write-Verbose "Inferred root path from single directory input: $resolvedRootPath"
    }
    else {
        # Default to current working directory for multiple/file inputs or if no specific root is given
        $resolvedRootPath = (Get-Location).Path
        Write-Verbose "Defaulting to current working directory as root path: $resolvedRootPath"
    }

    # Ensure root path ends with a directory separator for consistent relative path calculation
    if (-not ($resolvedRootPath.EndsWith([System.IO.Path]::DirectorySeparatorChar))) {
        $resolvedRootPath += [System.IO.Path]::DirectorySeparatorChar
    }
    Write-Verbose "Using effective root path for relative paths: $resolvedRootPath"


    # --- Generate the final output string ---
    foreach ($fileInfo in $uniqueFiles) {
        # Calculate relative path based on the determined root
        $relativePath = $fileInfo.FullName
        if ($relativePath.StartsWith($resolvedRootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            $relativePath = $relativePath.Substring($resolvedRootPath.Length)
        }
        # Normalize directory separators for display
        $relativePath = $relativePath.Replace([System.IO.Path]::DirectorySeparatorChar, '/')

        try {
            Write-Verbose "Reading file: $($fileInfo.FullName) (Relative: $relativePath)"
            # Use -Raw to get content as a single string, crucial for LLMs
            # Specify UTF8 encoding, common for code files
            $content = Get-Content -Path $fileInfo.FullName -Raw -Encoding UTF8 -ErrorAction Stop

            # Output the formatted section with markdown code fence
            "--- File: $relativePath ---"
            "``````"
            $content
            "``````"
            "" # Add a blank line for separation
        }
        catch {
            Write-Warning "Could not read content of file '$($fileInfo.FullName)': $($_.Exception.Message)"
            # Output an error message within the context for the specific file
            "--- File: $relativePath ---"
            "``````"
            "*** Error: Could not read file content. $($_.Exception.Message) ***"
            "``````"
            ""
        }
    }
    Write-Verbose "Context generation complete."
}