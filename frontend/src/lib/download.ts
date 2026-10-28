/** Hands a text to the browser as a file download. */
export function saveTextFile(filename: string, text: string, type = 'application/geo+json'): void {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.append(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
