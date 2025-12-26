import {
  isRouteErrorResponse,
  Links,
  Meta,
  NavLink,
  Outlet,
  Scripts,
  ScrollRestoration,
} from "react-router";

import type { Route } from "./+types/root";
import "./app.css";

export const links: Route.LinksFunction = () => [
  {
    rel: "icon",
    type: "image/png",
    sizes: "96x96",
    href: "/favicon-96x96.png"
  }, {
    rel: "icon",
    type: "image/svg+xml",
    href: "/favicon.svg"
  }, {
    rel: "shortcut icon",
    href: "/favicon.ico"
  },
  {
    rel: "app-touch-icon",
    sizes: "180x180",
    href: "/app-touch-icon.png"
  },
  {
    rel: "manifest",
    href: "/site.webmanifest"
  }
];

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <Meta />
        <Links />
      </head>
      <body>
        {children}
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

export default function App() {
  return <Outlet />;
}

export function ErrorBoundary({ error }: Route.ErrorBoundaryProps) {
  let message = "Oops!";
  let details = "An unexpected error occurred.";
  let stack: string | undefined;

  if (isRouteErrorResponse(error)) {
    message = error.status === 404 ? "404" : "Error";
    details =
      error.status === 404
        ? "The requested page could not be found."
        : error.statusText || details;
  } else if (import.meta.env.DEV && error && error instanceof Error) {
    details = error.message;
    stack = error.stack;
  }

  return (
    <main className="container flex justify-center items-center h-screen">
      <div className="flex flex-col gap-5">
        <h1>{message}</h1>
        <p>{details}</p>
        {stack && (
          <pre className="w-full p-4 overflow-x-auto">
            <code>{stack}</code>
          </pre>
        )}
        <NavLink to={""}><u>Click to Home &rarr;</u></NavLink>
      </div>
    </main>
  );
}
