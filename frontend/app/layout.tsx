import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "Parametrix · Ideas into geometry",
  description: "A local AI-powered parametric CAD workspace",
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
