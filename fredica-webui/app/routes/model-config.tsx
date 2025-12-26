import Sidebar from "~/components/sidebar/Sidebar";
import type { Route } from "./+types/_index";
import { useState } from "react";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "Fredica Model Config" },
        { name: "description", content: "Configuring an LLM model for Fredica" },
    ];
}

export default function Component({
    params,
}: Route.ComponentProps) {
    const [isSidebarOpen, setIsSidebarOpen] = useState(false);
    return (
        <SidebarLayout>
            <div>a</div>
        </SidebarLayout>
        // <Home />
        // <nav>
        //     <NavLink to={"/toolbox"}>Toolbox</NavLink>
        // </nav>
    )
} 