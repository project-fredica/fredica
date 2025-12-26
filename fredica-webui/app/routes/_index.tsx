
import { useState } from "react";
import { NavLink } from "react-router";
import { Layout } from "~/root";
import type { Route } from "./+types/_index";
import Home from "~/pages/home/App"
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "Fredica" },
        { name: "description", content: "Fredica: Elegant Precision for Learning" },
    ];
}

export default function Component({
    params,
}: Route.ComponentProps) {
    return (
        <SidebarLayout>
            <div>


            </div>
            <div>

            </div>
        </SidebarLayout>
        // <Home />
        // <nav>
        //     <NavLink to={"/toolbox"}>Toolbox</NavLink>
        // </nav>
    )
} 
